import Foundation
import SwiftUI
import HerCore
import Darwin
#if os(iOS)
import UIKit
#endif

@MainActor final class AppModel: ObservableObject {
    static let shared = AppModel()
    @Published var snapshot = Snapshot()
    @Published var busy = false
    @Published var error: String?
    @Published var available = false
    @Published var serviceStatus = ""
    @Published var calendarEvents: [CalendarEntry] = []
    @Published var calendarFrom = Calendar.current.startOfDay(for: Date())
    @Published var persian = UserDefaults.standard.object(forKey: "HerPersian") as? Bool ?? true {
        didSet { UserDefaults.standard.set(persian, forKey: "HerPersian") }
    }
    let device = NativeServices()
    let google = GoogleConnection()
    let background = BackgroundCoordinator()
    private var store: LocalStore?
    private var lockFD: Int32?
    private var activeTask: Task<Void, Never>?
    var pending: [Note] { snapshot.notes.filter { $0.reply == nil && $0.remote != true } }
    var configured: Bool { (try? snapshot.configuration.endpoint()) != nil }
    var shouldWake: Bool { snapshot.services?.followUp.enabled == true || snapshot.services?.syncEnabled == true || !pending.isEmpty }

    init() {
        do {
            let store = try LocalStore.applicationStore()
            snapshot = try store.load()
            self.store = store; available = true
        } catch { self.error = "Local data could not be loaded. It has not been overwritten. \(error.localizedDescription)" }
        device.action = { [weak self] id, action in self?.notificationAction(id, action: action) }
        configureGoogleCalendar()
    }
    func t(_ english: String, _ farsi: String) -> String { persian ? farsi : english }
    // Serialize the UI and optional macOS launch-agent processes across an entire run.
    private func beginExclusive() throws {
        guard lockFD == nil, let store, available else { throw HerError.storage("Local storage is unavailable or busy.") }
        try FileManager.default.createDirectory(at: store.url.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let fd = open(store.url.appendingPathExtension("lock").path, O_CREAT | O_RDWR, 0o600)
        guard fd >= 0 else { throw HerError.storage("Cannot open the local data lock.") }
        guard flock(fd, LOCK_EX | LOCK_NB) == 0 else {
            close(fd); throw HerError.storage(t("Another Her process is working. Retry shortly.", "پردازش دیگری از Her فعال است. کمی بعد دوباره تلاش کنید."))
        }
        lockFD = fd
        do { snapshot = try store.load(); if snapshot.services == nil { snapshot.services = ServiceState() } }
        catch { endExclusive(); throw error }
    }
    private func endExclusive() { if let fd = lockFD { flock(fd, LOCK_UN); close(fd); lockFD = nil } }
    func save(_ value: Snapshot, capture: Bool = true) throws {
        guard let store, available, lockFD != nil else { throw HerError.storage("Writing requires the local run lock.") }
        var value = value
        if capture { try SyncJournal.capture(previous: snapshot, next: &value) }
        try store.save(value); snapshot = value
    }
    func mutate(_ edit: (inout Snapshot) throws -> Void) throws {
        guard !busy else { throw HerError.storage("Her is working. Wait before changing settings or records.") }
        try beginExclusive(); defer { endExclusive() }
        var value = snapshot; try edit(&value); try save(value)
        background.schedule(enabled: shouldWake)
    }
    func configure(_ config: Configuration, key: String) throws {
        _ = try config.endpoint()
        guard !config.userName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !config.assistantName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw HerError.invalidTool(t("Enter both names.", "هر دو نام را وارد کنید."))
        }
        let oldKey = try CredentialStore.read()
        do { try mutate { value in
            try CredentialStore.write(key.trimmingCharacters(in: .whitespacesAndNewlines))
            value.configuration = config
        } } catch { try? CredentialStore.write(oldKey); throw error }
    }
    func enqueue(_ text: String) -> Bool {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= 16000 else { return false }
        do { try mutate { $0.notes.append(Note(text: text)) }; processQueue(); return true }
        catch { self.error = error.localizedDescription; return false }
    }
    private func processPending() async throws {
        guard configured else { return }
        let key = try CredentialStore.read()
        while let note = pending.first {
            try Task.checkCancellation()
            _ = try await Agent.respond(note: note, snapshot: snapshot, key: key, client: OpenAIClient(), device: device, persist: { [self] in try save($0) })
        }
    }
    func processQueue() {
        guard !busy, available, configured, !pending.isEmpty else { return }
        activeTask = Task { [self] in
            do { try await runExclusive { [self] in try await processPending(); await reconcile() } }
            catch { self.error = error.localizedDescription }
            activeTask = nil
        }
    }
    private func runExclusive(_ body: () async throws -> Void) async throws {
        guard !busy else { return }
        try beginExclusive(); busy = true; error = nil
        defer { busy = false; endExclusive(); background.schedule(enabled: shouldWake) }
        try await body()
    }
    func wakeup(forceSync: Bool = false) async throws {
        guard available, !busy else { return }
        try await runExclusive { [self] in
            await reconcile()
            if snapshot.services?.syncEnabled == true,
               forceSync || Date().timeIntervalSince(snapshot.services?.lastSync ?? .distantPast) >= 6 * 3600 {
                serviceStatus = t("Synchronizing…", "در حال همگام‌سازی…")
                do {
                    let token = try await google.token(scope: GoogleConnection.driveScope)
                    guard google.accountID == snapshot.services?.googleAccountID else { throw HerError.invalidTool("Google account changed. Sync stopped to avoid mixing accounts.") }
                    _ = try await DriveSync.run(snapshot: snapshot, token: token, drive: DriveHTTP(), persist: { [self] in try save($0, capture: false) })
                    configureGoogleCalendar(); await reconcile()
                } catch is CancellationError { throw CancellationError() }
                catch { self.error = error.localizedDescription }
            }
            try Task.checkCancellation()
            try await processPending()
            if configured, let state = snapshot.services, state.followUp.enabled, await device.notificationsAllowed() {
                let key = try CredentialStore.read()
                for pass in FollowUpPolicy.due(state: state, lowBattery: lowBattery) {
                    try Task.checkCancellation()
                    serviceStatus = t("Background review: ", "بازبینی خودکار: ") + pass.rawValue
                    _ = try await FollowUpRunner.run(pass: pass, snapshot: snapshot, key: key, client: OpenAIClient(), device: device, persist: { [self] in try save($0) })
                }
            }
            serviceStatus = error == nil ? t("Local checks completed", "بررسی محلی انجام شد") : t("Local checks completed; review the reported error", "بررسی محلی انجام شد؛ پیام خطا را بررسی کنید")
        }
    }
    private var lowBattery: Bool {
        #if os(iOS)
        UIDevice.current.isBatteryMonitoringEnabled = true
        return UIDevice.current.batteryLevel >= 0 && UIDevice.current.batteryLevel <= 0.15 && UIDevice.current.batteryState == .unplugged
        #else
        return ProcessInfo.processInfo.isLowPowerModeEnabled
        #endif
    }
    func refresh() {
        guard !busy else { return }
        activeTask = Task { [self] in
            do { try await wakeup(forceSync: true) } catch { self.error = error.localizedDescription }
            activeTask = nil
        }
    }
    func stop() { activeTask?.cancel() }
    func complete(_ record: Record) {
        do {
            try mutate { value in
                guard let index = value.records.firstIndex(where: { $0.id == record.id }) else { return }
                value.records[index].done.toggle(); value.records[index].modifiedAt = Date()
            }
            Task { await reconcileWithoutRun() }
        } catch { self.error = error.localizedDescription }
    }
    func delete(_ record: Record) { do { try mutate { $0.forget(record) }; Task { await reconcileWithoutRun() } } catch { self.error = error.localizedDescription } }
    func discard(_ note: Note) {
        do { try mutate { $0.notes.removeAll { $0.id == note.id && $0.reply == nil && $0.remote != true } } }
        catch { self.error = error.localizedDescription }
    }
    func updateSettings(_ settings: FollowUpSettings) async {
        do {
            guard (0..<1440).contains(settings.quietStart), (0..<1440).contains(settings.quietEnd), TimeZone(identifier: settings.timeZone) != nil else { throw HerError.invalidTool("Invalid quiet hours or timezone.") }
            if settings.enabled { try await device.requestNotifications() }
            try mutate { $0.services!.followUp = settings }
        } catch { self.error = error.localizedDescription }
    }
    func connectGoogle(calendar: Bool) async {
        guard !busy else { return }
        busy = true
        do {
            try await google.connect(calendar: calendar)
            if let old = snapshot.services?.googleAccountID, old != google.accountID {
                google.disconnect(); throw HerError.invalidTool("This local profile belongs to another Google account. Reconnect that account; a separate local profile is required to change accounts.")
            }
            busy = false
            try mutate { $0.services!.syncEnabled = true; $0.services!.googleCalendarEnabled = calendar; $0.services!.googleAccountID = google.accountID }
            configureGoogleCalendar(); refresh()
        } catch { busy = false; self.error = error.localizedDescription }
    }
    func disconnectGoogle() {
        do { try mutate { $0.services!.syncEnabled = false; $0.services!.googleCalendarEnabled = false }; google.disconnect(); configureGoogleCalendar() }
        catch { self.error = error.localizedDescription }
    }
    private func configureGoogleCalendar() {
        if snapshot.services?.googleCalendarEnabled == true {
            device.googleEvents = { [weak self] from, to in
                guard let self else { return [] }; return try await self.google.events(from: from, to: to)
            }
        } else { device.googleEvents = nil }
    }
    func loadCalendar() async {
        calendarEvents = InternalCalendar.entries(snapshot, from: calendarFrom, to: calendarFrom.addingTimeInterval(7 * 86400))
        do { calendarEvents += try await device.calendarEntries(from: calendarFrom, to: calendarFrom.addingTimeInterval(7 * 86400)); calendarEvents.sort { $0.start < $1.start } }
        catch { self.error = error.localizedDescription }
    }
    func confirmDelete(_ pending: PendingCalendarDelete) async {
        do {
            try await runExclusive { [self] in
                guard let actual = snapshot.services?.pendingDeletes.first(where: { $0.id == pending.id }),
                      Date().timeIntervalSince(actual.createdAt) < 15 * 60 else { throw HerError.invalidTool("Deletion request expired. Ask again before confirming.") }
                var value = snapshot
                if actual.eventID.hasPrefix("internal:") { try InternalCalendar.write(id: String(actual.eventID.dropFirst(9)), fields: [:], deleted: true, snapshot: &value) }
                else { _ = try await device.changeCalendar(CalendarChange(action: "delete", eventID: actual.eventID, journalID: actual.id)) }
                value.services!.pendingDeletes.removeAll { $0.id == actual.id }; try save(value)
            }
            await loadCalendar()
        } catch { self.error = error.localizedDescription }
    }
    func dismissDelete(_ pending: PendingCalendarDelete) {
        do { try mutate { $0.services!.pendingDeletes.removeAll { $0.id == pending.id } } }
        catch { self.error = error.localizedDescription }
    }
    func cancelReminder(_ reminder: ReminderItem) {
        do {
            try mutate { value in
                guard let i = value.services!.reminders.firstIndex(where: { $0.id == reminder.id }) else { return }
                value.services!.reminders[i].status = "CANCELLED"; value.services!.reminders[i].updatedAt = Date()
            }
            device.cancelReminder(id: reminder.id)
        } catch { self.error = error.localizedDescription }
    }
    private func notificationAction(_ id: String, action: String) {
        guard !busy else { return }
        Task { await reconcileWithoutRun() }
    }
    private func reconcileWithoutRun() async {
        do { try await runExclusive { [self] in await reconcile() } } catch { self.error = error.localizedDescription }
    }
    private func reconcile() async {
        let delivered = Set(await device.deliveredReminderIDs())
        var value = snapshot
        if value.services == nil { value.services = ServiceState() }
        let actions = device.pendingActions()
        for action in actions where value.services!.notificationActionReceipts?.contains(action.id) != true {
            if let i = value.services!.reminders.firstIndex(where: { $0.id == action.reminderID }), value.services!.reminders[i].status != "CANCELLED" {
                if action.action == "later" {
                    if value.services!.reminders[i].repeatRule != "NONE" {
                        var snooze = value.services!.reminders[i]; snooze.id = StableID.uuid(action.id).uuidString
                        snooze.repeatRule = "NONE"; snooze.fireAt = Date().addingTimeInterval(3600); snooze.createdAt = Date(); snooze.updatedAt = Date(); snooze.status = "SCHEDULED"
                        value.services!.reminders.append(snooze)
                    } else { value.services!.reminders[i].fireAt = Date().addingTimeInterval(3600); value.services!.reminders[i].status = "SCHEDULED" }
                } else if value.services!.reminders[i].repeatRule == "NONE" { value.services!.reminders[i].status = "FIRED" }
                value.services!.reminders[i].updatedAt = Date()
            }
            value.services!.notificationActionReceipts = Array(((value.services!.notificationActionReceipts ?? []) + [action.id]).suffix(1000))
        }
        for i in value.services!.reminders.indices where delivered.contains(value.services!.reminders[i].id)
            && value.services!.reminders[i].repeatRule == "NONE" && value.services!.reminders[i].status == "SCHEDULED" && (value.services!.reminders[i].fireAt ?? .distantFuture) <= Date() {
            value.services!.reminders[i].status = "FIRED"; value.services!.reminders[i].updatedAt = Date()
        }
        do { try save(value) } catch { self.error = error.localizedDescription; return }
        actions.forEach { device.acknowledge($0) }
        let failures = await device.reconcile(snapshot)
        if let failure = failures.first { serviceStatus = failure; error = failure }
    }
    func kindName(_ kind: RecordKind) -> String {
        switch kind {
        case .memory: return t("Memory", "حافظه")
        case .task: return t("Task", "کار")
        case .commitment: return t("Commitment", "تعهد")
        case .grocery: return t("Groceries", "خرید")
        case .waiting: return t("Waiting on", "در انتظار")
        case .decision: return t("Decision", "تصمیم")
        }
    }
}
