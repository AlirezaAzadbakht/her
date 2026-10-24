import Foundation
import EventKit
import UserNotifications
import HerCore

@MainActor final class NativeServices: NSObject, DeviceServices, UNUserNotificationCenterDelegate {
    struct PendingAction: Codable { var id: String; var reminderID: String; var action: String; var at: Date }
    private let eventStore = EKEventStore()
    private let center = UNUserNotificationCenter.current()
    private var notificationGenerations: [String: Int] = [:]
    var googleEvents: ((Date, Date) async throws -> [CalendarEntry])?
    var action: ((String, String) -> Void)?

    override init() {
        super.init()
        center.delegate = self
        let done = UNNotificationAction(identifier: "done", title: "Done / انجام شد", options: [])
        let later = UNNotificationAction(identifier: "later", title: "In one hour / یک ساعت بعد", options: [])
        center.setNotificationCategories([UNNotificationCategory(identifier: "HER_REMINDER", actions: [done, later], intentIdentifiers: [], options: [])])
    }
    var calendarGranted: Bool { EKEventStore.authorizationStatus(for: .event) == .fullAccess }
    private func occurrenceID(_ event: EKEvent) -> String? {
        guard let id = event.eventIdentifier else { return nil }
        return "system:" + Data(id.utf8).base64EncodedString() + ":" + String(Int64(event.startDate.timeIntervalSince1970 * 1000))
    }
    private func writeReceipt(_ event: EKEvent) -> String? {
        guard let line = event.notes?.components(separatedBy: "\n").last(where: { $0.hasPrefix("[Her write ") && $0.hasSuffix("]") }) else { return nil }
        return String(line.dropFirst(11).dropLast())
    }
    private func occurrence(_ reference: String) -> EKEvent? {
        let parts = reference.split(separator: ":", maxSplits: 2)
        guard parts.count == 3, parts[0] == "system", let bytes = Data(base64Encoded: String(parts[1])),
              let id = String(data: bytes, encoding: .utf8), let ms = Double(parts[2]), ms.isFinite else { return nil }
        let date = Date(timeIntervalSince1970: ms / 1000)
        return eventStore.events(matching: eventStore.predicateForEvents(withStart: date.addingTimeInterval(-1), end: date.addingTimeInterval(1), calendars: nil))
            .first { $0.eventIdentifier == id && abs($0.startDate.timeIntervalSince(date)) < 0.01 }
    }
    func requestCalendar() async throws {
        guard try await eventStore.requestFullAccessToEvents() else { throw HerError.invalidTool("Calendar permission denied. You can change it in system settings.") }
        eventStore.reset()
    }
    func requestNotifications() async throws {
        guard try await center.requestAuthorization(options: [.alert, .sound, .badge]) else { throw HerError.invalidTool("Notification permission denied. Enable it in system settings.") }
    }
    func calendarEntries(from: Date, to: Date) async throws -> [CalendarEntry] {
        var result: [CalendarEntry] = []
        if calendarGranted {
            let events = eventStore.events(matching: eventStore.predicateForEvents(withStart: from, end: to, calendars: nil))
            result = events.compactMap { e in
                guard let id = occurrenceID(e) else { return nil }
                return CalendarEntry(id: id, title: e.title ?? "", start: e.startDate, end: e.endDate, location: e.location, writable: e.calendar.allowsContentModifications, writeReceipt: writeReceipt(e))
            }
        }
        if let googleEvents {
            let cloud = try await googleEvents(from, to)
            let keys = Set(cloud.map { "\($0.title)|\($0.start.timeIntervalSince1970)|\($0.end.timeIntervalSince1970)" })
            result.removeAll { keys.contains("\($0.title)|\($0.start.timeIntervalSince1970)|\($0.end.timeIntervalSince1970)") }
            result += cloud
        } else if !calendarGranted {
            throw HerError.invalidTool("Calendar is unavailable. Enable system Calendar access in Settings or connect Google Calendar.")
        }
        return result.sorted { $0.start < $1.start }
    }
    func changeCalendar(_ change: CalendarChange) async throws -> String {
        guard calendarGranted else { throw HerError.invalidTool("Enable system Calendar access in Settings first.") }
        if change.action == "delete" {
            guard let id = change.eventID, let event = occurrence(id), event.calendar.allowsContentModifications else { throw HerError.invalidTool("Writable system occurrence not found. Read the current calendar before retrying.") }
            // Explicitly affect this occurrence, never the whole recurring series.
            try eventStore.remove(event, span: .thisEvent, commit: true)
            return id
        }
        guard let start = change.start, let end = change.end, end > start else { throw HerError.invalidTool("Invalid event time.") }
        let marker = "[Her write \(change.journalID)]"
        let event: EKEvent
        if change.action == "create" {
            // Recovers a successful EventKit write if the app died before persisting its receipt.
            let existing = eventStore.events(matching: eventStore.predicateForEvents(withStart: start.addingTimeInterval(-86400), end: end.addingTimeInterval(86400), calendars: nil))
                .first { $0.notes?.contains(marker) == true }
            if let existing, let id = occurrenceID(existing) { return id }
            guard let calendar = eventStore.defaultCalendarForNewEvents, calendar.allowsContentModifications else { throw HerError.invalidTool("No writable default calendar is configured.") }
            event = EKEvent(eventStore: eventStore); event.calendar = calendar
        } else {
            guard let id = change.eventID, let existing = occurrence(id), existing.calendar.allowsContentModifications else { throw HerError.invalidTool("Writable system occurrence not found; Google Calendar is read-only. Read the current calendar before retrying.") }
            event = existing
        }
        if let title = change.title { event.title = title }
        event.startDate = start; event.endDate = end
        if let location = change.location { event.location = location }
        if event.notes?.contains(marker) != true { event.notes = (event.notes ?? "") + "\n" + marker }
        try eventStore.save(event, span: .thisEvent, commit: true)
        guard let id = occurrenceID(event) else { throw HerError.invalidTool("Calendar saved but returned no identifier. Inspect Calendar before retrying.") }
        return id
    }
    func schedule(_ reminder: ReminderItem) async throws {
        let generation = (notificationGenerations[reminder.id] ?? 0) + 1
        notificationGenerations[reminder.id] = generation
        var settings = await center.notificationSettings()
        if settings.authorizationStatus == .notDetermined { try await requestNotifications(); settings = await center.notificationSettings() }
        guard [.authorized, .provisional].contains(settings.authorizationStatus) else { throw HerError.invalidTool("Notifications are not authorized. Reminder was not claimed as scheduled.") }
        guard reminder.trigger == "TIME", let time = reminder.fireAt, time > Date() || reminder.repeatRule != "NONE" else { throw HerError.invalidTool("A future clock time is required for a timed notification.") }
        let pending = await center.pendingNotificationRequests()
        let base = "reminder:" + reminder.id
        let own = pending.filter { $0.identifier == base || $0.identifier.hasPrefix(base + ":") }
        let capacity = 60 - (pending.count - own.count)
        guard capacity > 0 else {
            throw HerError.invalidTool("Notification capacity reached. Remove an existing reminder before adding another.")
        }
        let content = UNMutableNotificationContent()
        content.title = "Her"; content.body = reminder.message; content.sound = .default; content.categoryIdentifier = "HER_REMINDER"
        content.userInfo = ["reminderID": reminder.id]
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        var components = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: time)
        let repeating = reminder.repeatRule != "NONE"
        components.timeZone = calendar.timeZone
        if repeating {
            calendar.timeZone = TimeZone(identifier: reminder.timeZone ?? TimeZone.current.identifier) ?? .current
            components = calendar.dateComponents([.hour, .minute, .second], from: time)
            components.timeZone = calendar.timeZone
            if reminder.repeatRule == "WEEKLY" { components.weekday = calendar.component(.weekday, from: time) }
            if reminder.repeatRule == "MONTHLY" { components.day = calendar.component(.day, from: time) }
        }
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: repeating)
        var requests: [UNNotificationRequest] = []
        if repeating, let first = trigger.nextTriggerDate(), first < time.addingTimeInterval(-1) {
            // A repeating trigger has no start-date constraint. Schedule bounded absolute
            // occurrences until a later wake can safely install the ordinary repeat trigger.
            var occurrence = time
            for _ in 0..<min(capacity, 8) {
                var utc = Calendar(identifier: .gregorian); utc.timeZone = TimeZone(secondsFromGMT: 0)!
                var parts = utc.dateComponents([.year, .month, .day, .hour, .minute, .second], from: occurrence)
                parts.timeZone = utc.timeZone
                requests.append(UNNotificationRequest(identifier: base + ":" + String(Int64(occurrence.timeIntervalSince1970)), content: content, trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)))
                guard let next = Dates.nextFire(reminder, after: occurrence.addingTimeInterval(1), zone: reminder.timeZone ?? TimeZone.current.identifier) else { break }
                occurrence = next
            }
        } else { requests = [UNNotificationRequest(identifier: base, content: content, trigger: trigger)] }
        for request in requests { try await center.add(request) }
        guard notificationGenerations[reminder.id] == generation else { center.removePendingNotificationRequests(withIdentifiers: requests.map(\.identifier)); throw CancellationError() }
        let keeping = Set(requests.map(\.identifier))
        center.removePendingNotificationRequests(withIdentifiers: own.filter { !keeping.contains($0.identifier) }.map(\.identifier))
        UserDefaults.standard.set(true, forKey: "HerArmed:" + reminder.id)
    }
    func cancelReminder(id: String) {
        let generation = (notificationGenerations[id] ?? 0) + 1; notificationGenerations[id] = generation
        center.removePendingNotificationRequests(withIdentifiers: ["reminder:" + id])
        center.removeDeliveredNotifications(withIdentifiers: ["reminder:" + id])
        Task {
            let base = "reminder:" + id
            let pending = await center.pendingNotificationRequests()
            guard notificationGenerations[id] == generation else { return }
            center.removePendingNotificationRequests(withIdentifiers: pending.filter { $0.identifier.hasPrefix(base + ":") }.map(\.identifier))
            let delivered = await center.deliveredNotifications()
            center.removeDeliveredNotifications(withIdentifiers: delivered.filter { $0.request.content.userInfo["reminderID"] as? String == id }.map { $0.request.identifier })
        }
        UserDefaults.standard.removeObject(forKey: "HerArmed:" + id)
    }
    func reconcile(_ snapshot: Snapshot) async -> [String] {
        guard let state = snapshot.services else { return [] }
        let owned = state.reminders.filter { $0.deviceID == state.deviceID && $0.status == "SCHEDULED" && $0.trigger == "TIME" }
        let eligible = owned.filter { r in
            guard let type = r.onlyIfType, let id = r.onlyIfID else { return true }
            guard let row = state.syncRows[type + "/" + id], row["deletedAt"]?.number == nil else { return false }
            return type == "tasks" ? row["status"]?.text == "OPEN" : ["OPEN", "MISSED"].contains(row["status"]?.text ?? "")
        }
        let ids = Set(eligible.map { "reminder:" + $0.id })
        let pending = await center.pendingNotificationRequests()
        center.removePendingNotificationRequests(withIdentifiers: pending.filter { request in request.identifier.hasPrefix("reminder:") && !ids.contains(where: { request.identifier == $0 || request.identifier.hasPrefix($0 + ":") }) }.map(\.identifier))
        var errors: [String] = []
        for var reminder in eligible.sorted(by: { ($0.fireAt ?? .distantFuture) < ($1.fireAt ?? .distantFuture) }) {
            if reminder.repeatRule == "NONE", (reminder.fireAt ?? .distantFuture) < Date() {
                if UserDefaults.standard.bool(forKey: "HerArmed:" + reminder.id), !pending.contains(where: { $0.identifier == "reminder:" + reminder.id }) {
                    // A consumed OS request must not ring again merely because the user dismissed it.
                    continue
                }
                // Deliver missed clock reminders on the next wake, independently of the LLM policy.
                reminder.fireAt = Date().addingTimeInterval(2)
            }
            do { try await schedule(reminder) } catch { errors.append(error.localizedDescription) }
        }
        return errors
    }
    func deliveredReminderIDs() async -> [String] {
        await center.deliveredNotifications().compactMap { $0.request.content.userInfo["reminderID"] as? String }
    }
    private var actionsFolder: URL? { try? LocalStore.applicationStore().url.deletingLastPathComponent().appendingPathComponent("notification-actions") }
    private func queueAction(id: String, action: String, at: Date) {
        guard let folder = actionsFolder else { return }
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            let item = PendingAction(id: StableID.hash(id + action + Dates.string(at)), reminderID: id, action: action, at: at)
            let url = folder.appendingPathComponent(item.id + ".json")
            try JSONEncoder().encode(item).write(to: url, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            var excluded = folder, values = URLResourceValues(); values.isExcludedFromBackup = true; try excluded.setResourceValues(values)
            self.action?(id, action)
        } catch { /* Keep the delivered notification available if the OS cannot persist the action. */ }
    }
    func pendingActions() -> [PendingAction] {
        guard let folder = actionsFolder, let urls = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) else { return [] }
        return urls.compactMap { try? JSONDecoder().decode(PendingAction.self, from: Data(contentsOf: $0)) }.sorted { $0.at < $1.at }
    }
    func acknowledge(_ action: PendingAction) {
        guard let folder = actionsFolder, action.id == StableID.hash(action.reminderID + action.action + Dates.string(action.at)) else { return }
        try? FileManager.default.removeItem(at: folder.appendingPathComponent(action.id + ".json"))
    }
    func publishNotice(id: String, text: String) async throws {
        let settings = await center.notificationSettings()
        guard [.authorized, .provisional].contains(settings.authorizationStatus) else { throw HerError.invalidTool("Follow-up notification permission is missing.") }
        let content = UNMutableNotificationContent(); content.title = "Her"; content.body = text; content.sound = .default
        try await center.add(UNNotificationRequest(identifier: "notice:" + id, content: content, trigger: nil))
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
        if let id = notification.request.content.userInfo["reminderID"] as? String { let date = notification.date; Task { @MainActor [weak self] in self?.queueAction(id: id, action: "delivered", at: date) } }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        let id = response.notification.request.content.userInfo["reminderID"] as? String
        let actionID = response.actionIdentifier
        let date = response.notification.date
        Task { @MainActor [weak self] in if let id { self?.queueAction(id: id, action: actionID, at: date) }; completionHandler() }
    }
}
