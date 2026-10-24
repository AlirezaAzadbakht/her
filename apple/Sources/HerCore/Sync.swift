import Foundation

/// Wire format is the Android opToLine schema, including payloadJson as a JSON string.
public struct SyncOperation: Codable, Equatable, Sendable {
    public var id: String
    public var entityType: String
    public var entityId: String
    public var opType: String
    public var payloadJson: String
    public var createdAt: Int64
    public var deviceId: String
    public var seq: Int64
    public init(type: String, entityID: String, payload: SyncRow, deleted: Bool, state: inout ServiceState, now: Date) throws {
        id = UUID().uuidString; entityType = type; entityId = entityID; opType = deleted ? "DELETE" : "UPSERT"
        payloadJson = String(decoding: try JSONEncoder().encode(payload), as: UTF8.self)
        createdAt = Int64(now.timeIntervalSince1970 * 1000); deviceId = state.deviceID; seq = state.nextSequence
        state.nextSequence += 1
    }
}

public struct ChangeLogName: Equatable {
    public let device: String
    public let first: Int64
    public let last: Int64
    public init?(_ name: String) {
        guard let slash = name.lastIndex(of: "/"), name.hasSuffix(".ndjson") else { return nil }
        let range = name[name.index(after: slash)...].dropLast(7).split(separator: "-")
        guard range.count == 2, let first = Int64(range[0]), let last = Int64(range[1]), first > 0, last >= first else { return nil }
        device = String(name[..<slash]); self.first = first; self.last = last
        if device.isEmpty { return nil }
    }
}

public enum SyncMerge {
    public static func merge(existing: SyncRow?, incoming: SyncRow, type: String, device: String) -> SyncRow {
        guard let existing else { return incoming }
        let a = existing["updatedAt"]?.number ?? 0, b = incoming["updatedAt"]?.number ?? 0
        if type == "companion_records", existing["kind"]?.text == "PREFERENCES", incoming["kind"]?.text == "PREFERENCES" {
            if let deletion = existing["deletedAt"]?.number, incoming["deletedAt"]?.number == nil, (incoming["createdAt"]?.number ?? 0) > deletion { return incoming }
        }
        if existing["deletedAt"]?.number != nil { return existing }
        if incoming["deletedAt"]?.number != nil { return incoming }
        let wins = b > a || (b == a && device > (existing["deviceId"]?.text ?? ""))
        if ["learning_records", "companion_records"].contains(type) { return wins ? incoming : existing }
        var merged = wins ? existing.merging(incoming) { _, new in new } : incoming.merging(existing) { _, old in old }
        if type == "groceries", b == a, existing["status"]?.text == "PURCHASED" || incoming["status"]?.text == "PURCHASED" { merged["status"] = .string("PURCHASED") }
        return merged
    }
    public static func parse(_ text: String) throws -> [SyncOperation] {
        guard text.utf8.count <= 20_000_000 else { throw HerError.invalidTool("Sync log exceeds the safety limit.") }
        return try text.split(whereSeparator: \.isNewline).map { line in
            let op = try JSONDecoder().decode(SyncOperation.self, from: Data(line.utf8))
            guard op.seq > 0, !op.entityId.isEmpty, !op.entityType.isEmpty, ["UPSERT", "DELETE"].contains(op.opType), op.createdAt > 0 else { throw HerError.invalidTool("Invalid Drive operation; cursor was not advanced.") }
            return op
        }
    }
    public static func apply(_ ops: [SyncOperation], to snapshot: inout Snapshot) throws {
        var state = snapshot.services ?? ServiceState()
        for op in ops.sorted(by: { ($0.createdAt, $0.deviceId, $0.seq) < ($1.createdAt, $1.deviceId, $1.seq) }) {
            let key = op.entityType + "/" + op.entityId
            var incoming = try JSONDecoder().decode(SyncRow.self, from: Data(op.payloadJson.utf8))
            guard incoming["id"]?.text == op.entityId || op.opType == "DELETE" else { throw HerError.invalidTool("Sync entity ID mismatch.") }
            if op.entityType == "calendar_events", op.opType != "DELETE", incoming["source"]?.text != "INTERNAL" { continue }
            if op.opType == "DELETE" {
                incoming = state.syncRows[key] ?? ["id": .string(op.entityId)]
                incoming["deletedAt"] = .number(Double(op.createdAt)); incoming["updatedAt"] = .number(Double(op.createdAt))
            }
            state.syncRows[key] = merge(existing: state.syncRows[key], incoming: incoming, type: op.entityType, device: op.deviceId)
        }
        snapshot.services = state
        project(into: &snapshot)
    }
    public static func project(into snapshot: inout Snapshot) {
        guard var state = snapshot.services else { return }
        for (key, row) in state.syncRows {
            let type = String(key.split(separator: "/").first ?? "")
            guard let id = row["id"]?.text else { continue }
            let deleted = row["deletedAt"]?.number != nil
            let date = Date(timeIntervalSince1970: (row["createdAt"]?.number ?? row["updatedAt"]?.number ?? 0) / 1000)
            let uuid = UUID(uuidString: id) ?? StableID.uuid(type + id)
            if type == "user_profile", !deleted {
                if let name = row["userName"]?.text, !name.isEmpty { snapshot.configuration.userName = name }
                if let name = row["assistantName"]?.text, !name.isEmpty { snapshot.configuration.assistantName = name }
                continue
            }
            if type == "reminders" {
                if deleted { state.reminders.removeAll { $0.id == id }; continue }
                guard let message = row["message"]?.text else { continue }
                var r = ReminderItem(message: message, fireAt: row["fireAt"]?.number.map { Date(timeIntervalSince1970: $0 / 1000) }, deviceID: row["deviceId"]?.text ?? "", sourceID: row["sourceMessageId"]?.text ?? "", evidence: row["appleEvidence"]?.text ?? "")
                r.id = id; r.repeatRule = row["repeat"]?.text ?? "NONE"; r.trigger = row["trigger"]?.text ?? "TIME"
                r.status = row["status"]?.text ?? "SCHEDULED"; r.personID = row["personId"]?.text; r.place = row["place"]?.text
                r.onlyIfType = row["onlyIfEntityType"]?.text; r.onlyIfID = row["onlyIfEntityId"]?.text
                r.createdAt = date; r.updatedAt = Date(timeIntervalSince1970: (row["updatedAt"]?.number ?? 0) / 1000)
                r.timeZone = row["appleTimeZone"]?.text
                state.reminders.removeAll { $0.id == id }; state.reminders.append(r)
                continue
            }
            if type == "chat_messages" {
                // Remote PENDING is history only. Never enqueue another device's note.
                if deleted { snapshot.notes.removeAll { $0.id == uuid }; continue }
                guard row["deviceId"]?.text != state.deviceID, let content = row["content"]?.text else { continue }
                if row["role"]?.text == "USER", !snapshot.notes.contains(where: { $0.id == uuid }) {
                    var note = Note(text: content); note.id = uuid; note.createdAt = date; note.remote = true; note.syncID = id
                    snapshot.notes.append(note)
                }
                continue
            }
            let kind: RecordKind?
            switch type {
            case "short_term_memories", "long_term_memories", "user_understandings": kind = row["category"]?.text == "decision" ? .decision : .memory
            case "tasks": kind = .task
            case "commitments": kind = .commitment
            case "groceries": kind = .grocery
            case "open_loops": kind = .waiting
            default: kind = nil
            }
            guard let kind else { continue } // All other domains remain losslessly available as raw rows.
            if deleted || ["DROPPED", "ARCHIVED", "HISTORICAL"].contains(row["status"]?.text ?? "") {
                snapshot.records.removeAll { $0.id == uuid }; continue
            }
            guard let text = row["title"]?.text ?? row["content"]?.text ?? row["name"]?.text ?? row["description"]?.text else { continue }
            let source = row["sourceMessageId"]?.text ?? id
            var record = Record(kind: kind, text: text, evidence: row["appleEvidence"]?.text ?? "Imported from synced Android record", sourceMessageID: UUID(uuidString: source) ?? StableID.uuid(source))
            record.id = uuid; record.createdAt = date; record.modifiedAt = Date(timeIntervalSince1970: (row["updatedAt"]?.number ?? 0) / 1000)
            record.done = ["DONE", "PURCHASED", "RESOLVED"].contains(row["status"]?.text ?? "")
            record.dueAt = row["dueAt"]?.number.map { Date(timeIntervalSince1970: $0 / 1000) }
            record.syncType = type; record.syncID = id
            snapshot.records.removeAll { $0.id == uuid }; snapshot.records.append(record)
        }
        snapshot.records.sort { $0.createdAt < $1.createdAt }; snapshot.notes.sort { $0.createdAt < $1.createdAt }
        if let latest = state.syncRows.filter({ $0.key.hasPrefix("chat_messages/") && $0.value["role"]?.text == "ASSISTANT" && $0.value["deletedAt"]?.number == nil }).values.max(by: { ($0["createdAt"]?.number ?? 0) < ($1["createdAt"]?.number ?? 0) }),
           (latest["createdAt"]?.number ?? 0) >= (snapshot.notes.filter { $0.reply != nil }.map { ($0.replyAt ?? $0.createdAt).timeIntervalSince1970 * 1000 }.max() ?? 0), let reply = latest["content"]?.text { snapshot.latestReply = reply }
        snapshot.services = state
    }
}

public enum SyncJournal {
    public static func type(_ record: Record) -> String {
        if let type = record.syncType { return type }
        switch record.kind {
        case .memory: return "long_term_memories"
        case .task: return "tasks"
        case .commitment: return "commitments"
        case .grocery: return "groceries"
        case .waiting: return "open_loops"
        case .decision: return "long_term_memories"
        }
    }
    public static func capture(previous: Snapshot, next: inout Snapshot, now: Date = Date()) throws {
        var state = next.services ?? ServiceState()
        func ms(_ date: Date) -> JSONValue { .number(date.timeIntervalSince1970 * 1000) }
        func append(_ type: String, _ id: String, _ fields: SyncRow, deleted: Bool = false) throws {
            let key = type + "/" + id
            let old = state.syncRows[key] ?? [:]
            if old["deletedAt"]?.number != nil { return }
            if !deleted && fields.allSatisfy({ old[$0.key] == $0.value }) { return }
            var row = old.merging(fields) { _, new in new }
            row["id"] = .string(id); row["updatedAt"] = ms(now)
            if row["createdAt"] == nil { row["createdAt"] = ms(now) }
            if row["deviceId"] == nil || type != "reminders" { row["deviceId"] = .string(state.deviceID) }
            row["version"] = .number((old["version"]?.number ?? 0) + 1)
            if deleted { row["deletedAt"] = ms(now) }
            let op = try SyncOperation(type: type, entityID: id, payload: row, deleted: deleted, state: &state, now: now)
            state.pendingOps.append(op); state.syncRows[key] = row
        }
        if !next.configuration.userName.isEmpty, previous.configuration.userName != next.configuration.userName || previous.configuration.assistantName != next.configuration.assistantName || state.syncRows["user_profile/user-profile"] == nil {
            try append("user_profile", "user-profile", ["userName": .string(next.configuration.userName), "assistantName": .string(next.configuration.assistantName)])
        }
        for record in next.records {
            let type = type(record), id = record.syncID ?? record.id.uuidString
            if previous.records.first(where: { $0.id == record.id }) == record, state.syncRows[type + "/" + id] != nil { continue }
            let titleKey = type.contains("memories") || type == "user_understandings" ? "content" : (type == "groceries" ? "name" : (type == "open_loops" ? "description" : "title"))
            var fields: SyncRow = [titleKey: .string(record.text), "sourceMessageId": state.syncRows[type + "/" + id]?["sourceMessageId"] ?? .string(record.sourceMessageID.uuidString), "createdAt": ms(record.createdAt), "appleEvidence": .string(record.evidence)]
            fields["status"] = .string(record.done ? (type == "groceries" ? "PURCHASED" : (type == "open_loops" ? "RESOLVED" : "DONE")) : (type == "groceries" || type.contains("memories") || type == "user_understandings" ? "ACTIVE" : "OPEN"))
            if type == "tasks" || type == "commitments" { fields["dueAt"] = record.dueAt.map(ms) ?? .null }
            if type.contains("memories"), state.syncRows[type + "/" + id] == nil { fields["source"] = .string("USER_STATED"); fields["category"] = .string(record.kind == .decision ? "decision" : "general") }
            try append(type, id, fields)
        }
        for removed in previous.records where !next.records.contains(where: { $0.id == removed.id }) { try append(type(removed), removed.syncID ?? removed.id.uuidString, [:], deleted: true) }
        for note in next.notes where note.remote != true {
            try append("chat_messages", note.id.uuidString, ["role": .string("USER"), "content": .string(note.text), "createdAt": ms(note.createdAt), "status": .string(note.reply == nil ? "PENDING" : "SENT")])
            if let reply = note.reply { try append("chat_messages", StableID.uuid(note.id.uuidString + ":reply").uuidString, ["role": .string("ASSISTANT"), "content": .string(reply), "status": .string("SENT"), "createdAt": ms(note.replyAt ?? note.createdAt)]) }
        }
        for old in previous.notes where !next.notes.contains(where: { $0.id == old.id }) {
            try append("chat_messages", old.syncID ?? old.id.uuidString, [:], deleted: true)
            if old.remote != true { try append("chat_messages", StableID.uuid(old.id.uuidString + ":reply").uuidString, [:], deleted: true) }
        }
        for r in state.reminders {
            if previous.services?.reminders.first(where: { $0.id == r.id }) == r, state.syncRows["reminders/" + r.id] != nil { continue }
            try append("reminders", r.id, ["message": .string(r.message), "trigger": .string(r.trigger), "fireAt": r.fireAt.map(ms) ?? .null,
                "repeat": .string(r.repeatRule), "status": .string(r.status), "deviceId": .string(r.deviceID), "sourceMessageId": .string(r.sourceID),
                "appleEvidence": .string(r.evidence), "createdAt": ms(r.createdAt), "personId": r.personID.map(JSONValue.string) ?? .null,
                "place": r.place.map(JSONValue.string) ?? .null, "onlyIfEntityType": r.onlyIfType.map(JSONValue.string) ?? .null, "onlyIfEntityId": r.onlyIfID.map(JSONValue.string) ?? .null, "appleTimeZone": r.timeZone.map(JSONValue.string) ?? .null])
        }
        for removed in previous.services?.reminders ?? [] where !state.reminders.contains(where: { $0.id == removed.id }) { try append("reminders", removed.id, [:], deleted: true) }
        next.services = state
    }
}

public struct DriveFile: Codable, Sendable { public var id: String; public var name: String; public init(id: String, name: String) { self.id = id; self.name = name } }
public protocol DriveTransport {
    func list(token: String) async throws -> [DriveFile]
    func download(token: String, id: String) async throws -> String
    func upload(token: String, name: String, content: String) async throws
}

public struct DriveHTTP: DriveTransport {
    private let session: URLSession
    public init(session: URLSession = URLSession(configuration: .ephemeral)) { self.session = session }
    private func request(_ url: URL, token: String, body: Data? = nil, contentType: String? = nil) async throws -> Data {
        var r = URLRequest(url: url); r.httpMethod = body == nil ? "GET" : "POST"; r.httpBody = body
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization"); r.setValue(contentType, forHTTPHeaderField: "Content-Type"); r.timeoutInterval = 60
        let (data, response) = try await session.data(for: r)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { throw HerError.invalidTool("Google Drive request failed (\((response as? HTTPURLResponse)?.statusCode ?? 0)). Reconnect Google if needed.") }
        return data
    }
    public func list(token: String) async throws -> [DriveFile] {
        struct Page: Decodable { var files: [DriveFile]; var nextPageToken: String? }
        var result: [DriveFile] = [], cursor: String?
        repeat {
            var url = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
            url.queryItems = [.init(name: "spaces", value: "appDataFolder"), .init(name: "fields", value: "nextPageToken,files(id,name)"), .init(name: "pageSize", value: "1000")]
            if let cursor { url.queryItems?.append(.init(name: "pageToken", value: cursor)) }
            let page = try JSONDecoder().decode(Page.self, from: await request(url.url!, token: token))
            result += page.files; cursor = page.nextPageToken
        } while cursor != nil
        return result
    }
    public func download(token: String, id: String) async throws -> String {
        let url = URL(string: "https://www.googleapis.com/drive/v3/files")!.appendingPathComponent(id).appending(queryItems: [.init(name: "alt", value: "media")])
        return String(decoding: try await request(url, token: token), as: UTF8.self)
    }
    public func upload(token: String, name: String, content: String) async throws {
        let boundary = "her_" + UUID().uuidString
        let metadata = String(decoding: try JSONEncoder().encode(["name": JSONValue.string(name), "parents": .array([.string("appDataFolder")])]), as: UTF8.self)
        let body = "--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n\(metadata)\r\n--\(boundary)\r\nContent-Type: application/x-ndjson\r\n\r\n\(content)\r\n--\(boundary)--\r\n"
        _ = try await request(URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")!, token: token, body: Data(body.utf8), contentType: "multipart/related; boundary=\(boundary)")
    }
}

@MainActor public enum DriveSync {
    public static func run(snapshot: Snapshot, token: String, drive: DriveTransport,
                           persist: (Snapshot) throws -> Void) async throws -> Snapshot {
        var working = snapshot
        if working.services == nil { working.services = ServiceState() }
        while !working.services!.pendingOps.isEmpty {
            let pending = Array(working.services!.pendingOps.prefix(100))
            let first = pending.first!, last = pending.last!
            let encoder = JSONEncoder()
            let text = try pending.map { String(decoding: try encoder.encode($0), as: UTF8.self) }.joined(separator: "\n")
            try await drive.upload(token: token, name: "\(working.services!.deviceID)/\(first.seq)-\(last.seq).ndjson", content: text)
            let ids = Set(pending.map(\.id)); working.services!.pendingOps.removeAll { ids.contains($0.id) }; try persist(working)
        }
        let files = try await drive.list(token: token).compactMap { f in ChangeLogName(f.name).map { (f, $0) } }
            .filter { $0.1.device != working.services!.deviceID }.sorted { ($0.1.device, $0.1.first) < ($1.1.device, $1.1.first) }
        for (file, log) in files {
            try Task.checkCancellation()
            let cursor = working.services!.cursors[log.device] ?? 0
            if log.last <= cursor { continue }
            let ops = try SyncMerge.parse(await drive.download(token: token, id: file.id))
            guard !ops.isEmpty, ops.map(\.seq).min() == log.first, ops.map(\.seq).max() == log.last,
                  Int64(Set(ops.map(\.seq)).count) == log.last - log.first + 1,
                  ops.allSatisfy({ $0.deviceId == log.device && $0.seq >= log.first && $0.seq <= log.last }) else { throw HerError.invalidTool("Drive log filename/operation mismatch or sequence gap; cursor not advanced.") }
            var candidate = working
            try SyncMerge.apply(ops.filter { $0.seq > cursor }, to: &candidate)
            candidate.services!.cursors[log.device] = log.last
            try persist(candidate); working = candidate
        }
        working.services!.lastSync = Date(); try persist(working); return working
    }
}
