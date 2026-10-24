import Foundation
import CryptoKit

public enum JSONValue: Codable, Equatable, Sendable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null
    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([String: JSONValue].self) { self = .object(v) }
        else { self = .array(try c.decode([JSONValue].self)) }
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .string(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        }
    }
    public var text: String? { if case .string(let s) = self { return s }; return nil }
    public var number: Double? { if case .number(let n) = self { return n }; return nil }
    public var isNull: Bool { self == .null }
}
public typealias SyncRow = [String: JSONValue]

public struct ReminderItem: Codable, Identifiable, Equatable, Sendable {
    public var id: String = UUID().uuidString
    public var message: String
    public var fireAt: Date?
    public var repeatRule: String = "NONE"
    public var trigger: String = "TIME"
    public var personID: String?
    public var place: String?
    public var onlyIfType: String?
    public var onlyIfID: String?
    public var status = "SCHEDULED"
    public var deviceID: String
    public var sourceID: String
    public var evidence: String
    public var timeZone: String?
    public var createdAt = Date()
    public var updatedAt = Date()
    public init(message: String, fireAt: Date?, deviceID: String, sourceID: String, evidence: String) {
        self.message = message; self.fireAt = fireAt; self.deviceID = deviceID; self.sourceID = sourceID; self.evidence = evidence
        self.timeZone = TimeZone.current.identifier
    }
}

public struct FollowUpSettings: Codable, Equatable, Sendable {
    public var enabled = false
    public var hourlyDuringQuiet = false
    public var quietStart = 23 * 60 + 30
    public var quietEnd = 8 * 60
    public var timeZone = TimeZone.current.identifier
    public init() {}
}

public struct CalendarEntry: Codable, Identifiable, Sendable {
    public var id: String
    public var title: String
    public var start: Date
    public var end: Date
    public var location: String?
    public var writable: Bool
    public var writeReceipt: String?
    public init(id: String, title: String, start: Date, end: Date, location: String?, writable: Bool, writeReceipt: String? = nil) {
        self.id = id; self.title = title; self.start = start; self.end = end; self.location = location; self.writable = writable
        self.writeReceipt = writeReceipt
    }
}

public struct CalendarChange: Codable, Sendable {
    public var action: String
    public var eventID: String?
    public var title: String?
    public var start: Date?
    public var end: Date?
    public var location: String?
    public var journalID: String
    public init(action: String, eventID: String? = nil, title: String? = nil, start: Date? = nil, end: Date? = nil, location: String? = nil, journalID: String) {
        self.action = action; self.eventID = eventID; self.title = title; self.start = start; self.end = end; self.location = location; self.journalID = journalID
    }
}

public struct PendingCalendarDelete: Codable, Identifiable, Sendable {
    public var id = UUID().uuidString
    public var eventID: String
    public var title: String
    public var sourceNoteID: String
    public var start: Date?
    public var createdAt = Date()
    public init(eventID: String, title: String, sourceNoteID: String, start: Date? = nil) { self.eventID = eventID; self.title = title; self.sourceNoteID = sourceNoteID; self.start = start }
}

public struct ServiceState: Codable, Equatable, Sendable {
    public var deviceID = UUID().uuidString
    public var reminders: [ReminderItem] = []
    public var followUp = FollowUpSettings()
    public var lastHourly: Date?
    public var lastNightly: String?
    public var lastBriefing: String?
    public var lastSync: Date?
    public var noticeHistory: [String: Date] = [:]
    public var pendingDeletes: [PendingCalendarDelete] = []
    public var calendarJournal: [String: String] = [:]
    public var syncRows: [String: SyncRow] = [:]
    public var pendingOps: [SyncOperation] = []
    public var cursors: [String: Int64] = [:]
    public var nextSequence: Int64 = 1
    public var syncEnabled = false
    public var googleCalendarEnabled = false
    public var googleAccountID: String?
    public var notificationActionReceipts: [String]?
    public var contextFloor: Date?
    public init() {}
}

extension PendingCalendarDelete: Equatable {}

@MainActor public protocol DeviceServices: AnyObject {
    func calendarEntries(from: Date, to: Date) async throws -> [CalendarEntry]
    func changeCalendar(_ change: CalendarChange) async throws -> String
    func schedule(_ reminder: ReminderItem) async throws
    func cancelReminder(id: String)
    func publishNotice(id: String, text: String) async throws
}

public enum Dates {
    public static func parse(_ input: String) throws -> Date {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: input) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        guard let date = formatter.date(from: input) else { throw HerError.invalidTool("Use an ISO 8601 date including a UTC offset, e.g. 2026-10-24T18:00:00+03:30.") }
        return date
    }
    public static func string(_ date: Date) -> String { ISO8601DateFormatter().string(from: date) }
    public static func day(_ date: Date, zone: String) -> String {
        let f = DateFormatter(); f.calendar = Calendar(identifier: .gregorian); f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: zone); f.dateFormat = "yyyy-MM-dd"; return f.string(from: date)
    }
    public static func calendar(_ zone: String) -> Calendar {
        var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: zone) ?? .current; return c
    }
    public static func quiet(_ date: Date, settings: FollowUpSettings) -> Bool {
        let c = calendar(settings.timeZone), minute = c.component(.hour, from: date) * 60 + c.component(.minute, from: date)
        let start = settings.quietStart, end = settings.quietEnd
        if start == end { return false }
        return start < end ? (minute >= start && minute < end) : (minute >= start || minute < end)
    }
    public static func nextFire(_ reminder: ReminderItem, after now: Date, zone: String) -> Date? {
        guard let date = reminder.fireAt else { return nil }
        if date > now { return date }
        guard reminder.repeatRule != "NONE" else { return nil }
        let c = calendar(zone)
        var parts = c.dateComponents([.hour, .minute, .second], from: date)
        if reminder.repeatRule == "WEEKLY" { parts.weekday = c.component(.weekday, from: date) }
        if reminder.repeatRule == "MONTHLY" { parts.day = c.component(.day, from: date) }
        return c.nextDate(after: now, matching: parts, matchingPolicy: .nextTimePreservingSmallerComponents)
    }
}

public enum StableID {
    public static func hash(_ text: String) -> String { SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined() }
    public static func uuid(_ text: String) -> UUID {
        let h = Array(hash(text).prefix(32)); let s = String(h)
        return UUID(uuidString: "\(s.prefix(8))-\(s.dropFirst(8).prefix(4))-\(s.dropFirst(12).prefix(4))-\(s.dropFirst(16).prefix(4))-\(s.suffix(12))")!
    }
}
