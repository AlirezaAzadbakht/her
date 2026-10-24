import Foundation

public struct Configuration: Codable, Equatable, Sendable {
    public var baseURL = ""
    public var model = ""
    public var userName = ""
    public var assistantName = "Her"
    public init() {}

    public func endpoint() throws -> URL {
        guard baseURL.count <= 2048, model.count <= 200, userName.count <= 100, assistantName.count <= 100,
              let components = URLComponents(string: baseURL.trimmingCharacters(in: .whitespacesAndNewlines)),
              let host = components.host, components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.scheme == "https" || (components.scheme == "http" && ["localhost", "127.0.0.1", "::1"].contains(host)),
              let url = components.url, !model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw HerError.invalidConfiguration
        }
        return url.appendingPathComponent("chat/completions")
    }
}

public enum RecordKind: String, Codable, CaseIterable, Sendable {
    case memory, task, commitment, grocery, waiting, decision
}

public struct Record: Codable, Identifiable, Equatable, Sendable {
    public var id: UUID
    public var kind: RecordKind
    public var text: String
    public var evidence: String
    public var sourceMessageID: UUID
    public var createdAt: Date
    public var done: Bool
    public var modifiedAt: Date?
    public var dueAt: Date?
    public var syncType: String?
    public var syncID: String?
    public init(kind: RecordKind, text: String, evidence: String, sourceMessageID: UUID) {
        self.id = UUID(); self.kind = kind; self.text = text; self.evidence = evidence
        self.sourceMessageID = sourceMessageID; self.createdAt = Date(); self.done = false
    }
}

public struct Note: Codable, Identifiable, Equatable, Sendable {
    public var id: UUID
    public var text: String
    public var createdAt: Date
    public var reply: String?
    public var replyAt: Date?
    public var remote: Bool?
    public var syncID: String?
    public var receipts: [String: String]?
    public init(text: String) { id = UUID(); self.text = text; createdAt = Date() }
}

public struct Snapshot: Codable, Equatable, Sendable {
    public var configuration = Configuration()
    public var records: [Record] = []
    public var notes: [Note] = []
    public var latestReply = ""
    public var services: ServiceState? = ServiceState()
    public init() {}

    /// Clear conversation copies as well as all records derived from the same note.
    /// Other pending notes remain queued. Other structured records remain searchable.
    public mutating func forget(_ record: Record) {
        records.removeAll { $0.sourceMessageID == record.sourceMessageID }
        notes.removeAll { $0.id == record.sourceMessageID || $0.reply != nil }
        services?.reminders.removeAll { $0.sourceID.lowercased() == record.sourceMessageID.uuidString.lowercased() }
        services?.pendingDeletes.removeAll { $0.sourceNoteID.lowercased() == record.sourceMessageID.uuidString.lowercased() }
        latestReply = ""
    }
}

public enum HerError: LocalizedError {
    case invalidConfiguration, invalidResponse, http(Int), toolBudget, invalidTool(String), storage(String)
    public var errorDescription: String? {
        switch self {
        case .invalidConfiguration: return "Enter a model and an HTTPS base URL such as https://provider.example/v1. HTTP is allowed only on localhost."
        case .invalidResponse: return "The provider returned an unsupported or empty response. Your note remains queued."
        case .http(let status): return "The provider returned HTTP \(status). Check credentials, model and service availability. Your note remains queued."
        case .toolBudget: return "The assistant reached its tool limit. Saved records remain; your note is queued for retry."
        case .invalidTool(let reason): return reason
        case .storage(let message): return message
        }
    }
}
