import Foundation

public enum ServiceTools {
    private static func tool(_ name: String, _ description: String, fields: [String: String], required: [String]) -> [String: Any] {
        ["type": "function", "function": ["name": name, "description": description,
            "parameters": ["type": "object", "properties": fields.mapValues { ["type": "string", "description": $0] }, "required": required, "additionalProperties": false]]]
    }
    public static var schemas: [[String: Any]] { [
        tool("set_reminder", "Create a timed device notification independent of the LLM/network, only after user request. Relative dates refer to note sent time. For conditional reminders save the matching task/commitment first. Confirm actual tool result including time. No clock alarms.", fields: ["message": "Reminder in user's language", "when": "ISO 8601 including offset", "repeat": "NONE, DAILY, WEEKLY or MONTHLY", "onlyIfType": "Optional tasks or commitments", "onlyIfID": "Optional existing task/commitment ID, only notify if still open", "evidence": "Exact contiguous current user quote"], required: ["message", "when", "evidence"]),
        tool("update_reminder", "Move, change or cancel an existing reminder on this device. Do not invent an ID.", fields: ["id": "Reminder ID", "message": "New text (optional)", "when": "New ISO 8601 time (optional)", "repeat": "NONE, DAILY, WEEKLY or MONTHLY", "status": "SCHEDULED or CANCELLED", "evidence": "Exact user request quote"], required: ["id", "evidence"]),
        tool("list_reminders", "Read local and synced reminders; only the creator device schedules notifications.", fields: [:], required: []),
        tool("set_context_reminder", "Save a reminder for the next conversational mention of a person or place. This has no timed notification or geofencing. Set exactly one of person/place.", fields: ["message": "Reminder text", "person": "Exact person's name, if person-triggered", "place": "Place name, if place-triggered", "evidence": "Exact user request quote"], required: ["message", "evidence"]),
        tool("complete_context_reminder", "Mark a person/place reminder fulfilled only when the current user note explicitly establishes the trigger and you bring up its message in your reply.", fields: ["id": "Context reminder ID", "evidence": "Exact current user quote establishing the encounter/place"], required: ["id", "evidence"]),
        tool("get_calendar_events", "Read system calendar (when authorized) and Google read-only calendar (when connected). Always read before creating or moving events. Up to 400 days.", fields: ["from": "ISO 8601 start including offset", "to": "ISO 8601 end including offset"], required: ["from", "to"]),
        tool("create_calendar_event", "Create an event in the writable system calendar by default, or in the synced internal calendar when explicitly requested. Reject overlaps; ask before resolving conflicts.", fields: ["title": "Title", "start": "ISO 8601 including offset", "end": "ISO 8601 including offset", "location": "Optional location", "target": "SYSTEM (default) or INTERNAL", "evidence": "Exact user request quote"], required: ["title", "start", "end", "evidence"]),
        tool("update_calendar_event", "Update one exact writable system calendar event ID. Do not recreate it. No Google read-only mutations.", fields: ["id": "Exact system ID", "title": "Optional title", "start": "ISO 8601 including offset", "end": "ISO 8601 including offset", "location": "Optional location", "evidence": "Exact user request quote"], required: ["id", "start", "end", "evidence"]),
        tool("request_calendar_delete", "Request deletion of an exact writable calendar event. This does not delete it: the user must confirm in the app's pending-deletion control.", fields: ["id": "Exact event ID", "evidence": "Exact user deletion request quote"], required: ["id", "evidence"]),
        tool("update_record", "Mark one saved task/commitment/grocery/waiting record complete or reopen it; optionally set a due date. No deletes.", fields: ["id": "Record UUID", "done": "true or false", "due": "Optional ISO 8601 due time", "evidence": "Exact user request quote"], required: ["id", "done", "evidence"])
    ] }

    @MainActor public static func execute(_ call: ToolCall, note: Note, snapshot: inout Snapshot, device: DeviceServices?, autonomous: Bool = false) async throws -> String {
        guard let data = call.function.arguments.data(using: .utf8), data.count <= 16000,
              let args = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { throw HerError.invalidTool("Invalid tool arguments.") }
        let schemas = schemas
        guard let function = schemas.compactMap({ $0["function"] as? [String: Any] }).first(where: { $0["name"] as? String == call.function.name }),
              let parameters = function["parameters"] as? [String: Any], let properties = parameters["properties"] as? [String: Any],
              let required = parameters["required"] as? [String], Set(required).isSubset(of: Set(args.keys)), Set(args.keys).isSubset(of: Set(properties.keys)) else { throw HerError.invalidTool("Invalid service tool schema.") }
        let readOnly = ["list_reminders", "get_calendar_events"].contains(call.function.name)
        if !readOnly {
            guard !autonomous else { throw HerError.invalidTool("Autonomous passes cannot change reminders, user records or external calendar events.") }
            guard let evidence = args["evidence"], !evidence.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, note.text.contains(evidence) else { throw HerError.invalidTool("A verbatim current user request is required.") }
        }
        if snapshot.services == nil { snapshot.services = ServiceState() }
        func encode<T: Encodable>(_ v: T) throws -> String { String(decoding: try JSONEncoder().encode(v), as: UTF8.self) }
        func internalEntries(_ from: Date, _ to: Date) -> [CalendarEntry] { InternalCalendar.entries(snapshot, from: from, to: to) }
        switch call.function.name {
        case "list_reminders": return try encode(snapshot.services!.reminders)
        case "set_context_reminder":
            guard let message = args["message"], !message.isEmpty, message.count <= 2000,
                  (args["person"] != nil) != (args["place"] != nil) else { throw HerError.invalidTool("Specify exactly one person or place and a nonempty message.") }
            let name = (args["person"] ?? args["place"] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, name.count <= 200, note.text.contains(name) else { throw HerError.invalidTool("Use the exact person/place name from the user note.") }
            let id = StableID.uuid(note.id.uuidString + ":context-reminder:" + args["evidence"]!).uuidString
            if snapshot.services!.reminders.contains(where: { $0.id == id }) { return "Context reminder already saved: \(id)" }
            var r = ReminderItem(message: message, fireAt: nil, deviceID: snapshot.services!.deviceID, sourceID: note.id.uuidString, evidence: args["evidence"]!)
            r.id = id
            if args["person"] != nil {
                r.trigger = "PERSON"
                if let person = snapshot.services!.syncRows.first(where: { $0.key.hasPrefix("people/") && $0.value["name"]?.text == name && $0.value["deletedAt"]?.number == nil }) { r.personID = person.value["id"]?.text }
                else {
                    let personID = UUID().uuidString, now = Date()
                    let row: SyncRow = ["id": .string(personID), "name": .string(name), "createdAt": .number(now.timeIntervalSince1970 * 1000), "updatedAt": .number(now.timeIntervalSince1970 * 1000), "deviceId": .string(snapshot.services!.deviceID), "version": .number(1)]
                    let op = try SyncOperation(type: "people", entityID: personID, payload: row, deleted: false, state: &snapshot.services!, now: now)
                    snapshot.services!.pendingOps.append(op); snapshot.services!.syncRows["people/" + personID] = row; r.personID = personID
                }
            } else { r.trigger = "PLACE"; r.place = name }
            snapshot.services!.reminders.append(r)
            return "Context reminder saved: \(id). It is surfaced when you report the encounter/place in chat; no clock time, location tracking or notification was invented."
        case "complete_context_reminder":
            guard let i = snapshot.services!.reminders.firstIndex(where: { $0.id == args["id"] }), snapshot.services!.reminders[i].trigger != "TIME" else { throw HerError.invalidTool("Context reminder not found.") }
            snapshot.services!.reminders[i].status = "FIRED"; snapshot.services!.reminders[i].updatedAt = Date()
            return "Context reminder fulfilled. Include the reminder in your reply."
        case "set_reminder":
            guard let device else { throw HerError.invalidTool("Device notifications are unavailable.") }
            guard let message = args["message"], !message.isEmpty, message.count <= 2000 else { throw HerError.invalidTool("Invalid reminder text.") }
            let when = try Dates.parse(args["when"]!)
            guard when > Date() else { throw HerError.invalidTool("The reminder time has already passed. Ask for a new time; do not silently move it.") }
            let rule = args["repeat"] ?? "NONE"
            guard ["NONE", "DAILY", "WEEKLY", "MONTHLY"].contains(rule) else { throw HerError.invalidTool("Invalid repeat rule.") }
            let dedupe = StableID.uuid(note.id.uuidString + ":reminder:" + args["evidence"]!).uuidString
            if let existing = snapshot.services!.reminders.first(where: { $0.id == dedupe }) {
                if existing.status == "SCHEDULED" { try await device.schedule(existing) }
                return "Reminder already saved: \(existing.id), time \(existing.fireAt.map(Dates.string) ?? "unknown"), status \(existing.status)"
            }
            var r = ReminderItem(message: message, fireAt: when, deviceID: snapshot.services!.deviceID, sourceID: note.id.uuidString, evidence: args["evidence"]!)
            r.id = dedupe; r.repeatRule = rule
            if args["onlyIfType"] != nil || args["onlyIfID"] != nil {
                guard let type = args["onlyIfType"], ["tasks", "commitments"].contains(type), let id = args["onlyIfID"],
                      let record = snapshot.records.first(where: { (SyncJournal.type($0) == type) && ($0.syncID ?? $0.id.uuidString).lowercased() == id.lowercased() }), !record.done else { throw HerError.invalidTool("Conditional reminder requires an existing open task or commitment.") }
                r.onlyIfType = type; r.onlyIfID = record.syncID ?? record.id.uuidString
            }
            try await device.schedule(r)
            snapshot.services!.reminders.append(r)
            return "Notification scheduled: \(r.id), \(Dates.string(when)), repeat \(rule). Delivery depends on notification permission and system Focus settings. A delayed-start repeating series may use a bounded rolling schedule until the next app/background wake; never claim unlimited guaranteed delivery."
        case "update_reminder":
            guard let device, let index = snapshot.services!.reminders.firstIndex(where: { $0.id == args["id"] }) else { throw HerError.invalidTool("Reminder not found or notifications unavailable.") }
            var r = snapshot.services!.reminders[index]
            if let message = args["message"] { guard !message.isEmpty, message.count <= 2000 else { throw HerError.invalidTool("Invalid reminder text.") }; r.message = message }
            if let when = args["when"] { r.fireAt = try Dates.parse(when) }
            if let rule = args["repeat"] { guard ["NONE", "DAILY", "WEEKLY", "MONTHLY"].contains(rule) else { throw HerError.invalidTool("Invalid repeat rule.") }; r.repeatRule = rule }
            if let status = args["status"] { guard ["SCHEDULED", "CANCELLED"].contains(status) else { throw HerError.invalidTool("Invalid reminder status.") }; r.status = status }
            r.updatedAt = Date()
            if r.status == "CANCELLED" { device.cancelReminder(id: r.id) }
            else if r.deviceID == snapshot.services!.deviceID { try await device.schedule(r) }
            snapshot.services!.reminders[index] = r
            return "Reminder saved: \(r.id), status \(r.status). \(r.deviceID == snapshot.services!.deviceID ? "This device owns delivery." : "Creator device will apply the change after syncing; it may not yet have received it.")"
        case "get_calendar_events":
            let from = try Dates.parse(args["from"]!), to = try Dates.parse(args["to"]!)
            guard to > from, to.timeIntervalSince(from) <= 400 * 86400 else { throw HerError.invalidTool("Invalid calendar range (maximum 400 days).") }
            var events = internalEntries(from, to)
            var coverage = "Internal calendar only; no system/Google access available."
            if let device {
                do { events += try await device.calendarEntries(from: from, to: to); coverage = "Internal plus currently authorized device/Google calendar sources; no claim of other account coverage." }
                catch { coverage = "Internal calendar only. External calendar read failed: \(error.localizedDescription)" }
            }
            struct Result: Encodable { var events: [CalendarEntry]; var coverage: String }
            return try encode(Result(events: events.sorted { $0.start < $1.start }, coverage: coverage))
        case "create_calendar_event", "update_calendar_event":
            let start = try Dates.parse(args["start"]!), end = try Dates.parse(args["end"]!)
            guard end > start, end.timeIntervalSince(start) <= 400 * 86400 else { throw HerError.invalidTool("Invalid event duration.") }
            let key = note.id.uuidString + ":" + call.function.name + ":" + args["evidence"]!
            if let id = snapshot.services!.calendarJournal[key] { return "Previously saved event ID: \(id). Do not recreate it." }
            let internalTarget = args["target"] == "INTERNAL" || args["id"]?.hasPrefix("internal:") == true
            if let target = args["target"], !["INTERNAL", "SYSTEM"].contains(target) { throw HerError.invalidTool("Invalid calendar target.") }
            var events = internalEntries(start, end)
            if let device {
                do { events += try await device.calendarEntries(from: start, to: end) }
                catch { if !internalTarget { throw error } }
            } else if !internalTarget { throw HerError.invalidTool("Calendar access unavailable.") }
            guard !events.contains(where: { $0.id != args["id"] && $0.start < end && $0.end > start }) else { throw HerError.invalidTool("Calendar conflict. Ask the user which event should move; no change was made.") }
            let creating = call.function.name == "create_calendar_event"
            if creating { guard let title = args["title"], !title.isEmpty, title.count <= 1000 else { throw HerError.invalidTool("A valid title is required.") } }
            if internalTarget {
                let id = creating ? StableID.uuid(key).uuidString : String((args["id"] ?? "").dropFirst(9))
                if !creating, snapshot.services!.syncRows["calendar_events/" + id] == nil { throw HerError.invalidTool("Internal event not found.") }
                try InternalCalendar.write(id: id, fields: ["title": args["title"].map(JSONValue.string) ?? snapshot.services!.syncRows["calendar_events/" + id]?["title"] ?? .string(""), "startAt": .number(start.timeIntervalSince1970 * 1000), "endAt": .number(end.timeIntervalSince1970 * 1000), "location": args["location"].map(JSONValue.string) ?? snapshot.services!.syncRows["calendar_events/" + id]?["location"] ?? .null], snapshot: &snapshot)
                snapshot.services!.calendarJournal[key] = "internal:" + id
                return "Synced internal event saved: internal:\(id), \(Dates.string(start)) until \(Dates.string(end)). This is not a device-calendar event."
            }
            guard let device else { throw HerError.invalidTool("Calendar access unavailable.") }
            let change = CalendarChange(action: creating ? "create" : "update", eventID: args["id"], title: args["title"], start: start, end: end, location: args["location"], journalID: StableID.hash(key))
            let id = try await device.changeCalendar(change)
            snapshot.services!.calendarJournal[key] = id
            return "Calendar event saved: \(id), \(Dates.string(start)) until \(Dates.string(end))."
        case "request_calendar_delete":
            let now = Date(), from = now.addingTimeInterval(-400 * 86400), to = now.addingTimeInterval(400 * 86400)
            var events = internalEntries(from, to)
            if let device { events += (try? await device.calendarEntries(from: from, to: to)) ?? [] }
            guard let event = events.first(where: { $0.id == args["id"] }), event.writable else { throw HerError.invalidTool("Writable system event not found. Google events are read-only.") }
            if !snapshot.services!.pendingDeletes.contains(where: { $0.eventID == event.id }) {
                snapshot.services!.pendingDeletes.append(PendingCalendarDelete(eventID: event.id, title: event.title, sourceNoteID: note.id.uuidString))
            }
            return "Deletion requested, NOT executed. Tell the user to confirm the named event in Calendar → Pending deletions. You cannot confirm it yourself."
        case "update_record":
            guard let index = snapshot.records.firstIndex(where: { $0.id.uuidString.lowercased() == args["id"]?.lowercased() }),
                  let done = args["done"], ["true", "false"].contains(done) else { throw HerError.invalidTool("Record not found or invalid status.") }
            snapshot.records[index].done = done == "true"; snapshot.records[index].modifiedAt = Date()
            if let due = args["due"] { snapshot.records[index].dueAt = try Dates.parse(due) }
            if done == "true" {
                let record = snapshot.records[index]
                for r in snapshot.services!.reminders where r.onlyIfType == SyncJournal.type(record) && r.onlyIfID == (record.syncID ?? record.id.uuidString) { device?.cancelReminder(id: r.id) }
            }
            return "Record updated: \(snapshot.records[index].id.uuidString)"
        default: throw HerError.invalidTool("Unsupported tool.")
        }
    }
}

public enum InternalCalendar {
    public static func entries(_ snapshot: Snapshot, from: Date, to: Date) -> [CalendarEntry] {
        (snapshot.services?.syncRows ?? [:]).filter { $0.key.hasPrefix("calendar_events/") }.values.compactMap { row in
            guard row["source"]?.text == "INTERNAL", row["deletedAt"]?.number == nil, let id = row["id"]?.text,
                  let start = row["startAt"]?.number else { return nil }
            let s = Date(timeIntervalSince1970: start / 1000), end = Date(timeIntervalSince1970: (row["endAt"]?.number ?? start + 3600000) / 1000)
            guard s < to, end > from else { return nil }
            return CalendarEntry(id: "internal:" + id, title: row["title"]?.text ?? "", start: s, end: end, location: row["location"]?.text, writable: true)
        }
    }
    public static func write(id: String, fields: SyncRow, deleted: Bool = false, snapshot: inout Snapshot, now: Date = Date()) throws {
        if snapshot.services == nil { snapshot.services = ServiceState() }
        let key = "calendar_events/" + id
        var row = snapshot.services!.syncRows[key] ?? ["id": .string(id), "createdAt": .number(now.timeIntervalSince1970 * 1000), "source": .string("INTERNAL")]
        guard row["source"]?.text == "INTERNAL", row["deletedAt"]?.number == nil else { throw HerError.invalidTool("Internal event is missing or deleted.") }
        row.merge(fields) { _, new in new }; row["updatedAt"] = .number(now.timeIntervalSince1970 * 1000)
        row["deviceId"] = .string(snapshot.services!.deviceID); row["version"] = .number((row["version"]?.number ?? 0) + 1)
        if deleted { row["deletedAt"] = .number(now.timeIntervalSince1970 * 1000) }
        let op = try SyncOperation(type: "calendar_events", entityID: id, payload: row, deleted: deleted, state: &snapshot.services!, now: now)
        snapshot.services!.syncRows[key] = row; snapshot.services!.pendingOps.append(op)
    }
}
