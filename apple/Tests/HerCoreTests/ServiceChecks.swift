import Foundation
import HerCore

@MainActor enum ServiceChecks {
    static func call(_ name: String, _ args: [String: String]) -> ToolCall {
        let data = try! JSONEncoder().encode(args)
        return ToolCall(id: UUID().uuidString, function: .init(name: name, arguments: String(decoding: data, as: UTF8.self)))
    }
    static func run() async throws -> [String] {
        var passed: [String] = []
        func expect(_ condition: @autoclosure () throws -> Bool, _ message: String) throws { try CoreChecks.expect(try condition(), message) }
        let now = try Dates.parse("2026-10-24T12:00:00+03:30")
        var settings = FollowUpSettings(); settings.enabled = true; settings.timeZone = "Asia/Tehran"
        try expect(Dates.quiet(try Dates.parse("2026-10-24T23:30:00+03:30"), settings: settings), "Quiet start inclusive")
        try expect(!Dates.quiet(try Dates.parse("2026-10-25T08:00:00+03:30"), settings: settings), "Quiet end exclusive")
        settings.quietStart = 12 * 60; settings.quietEnd = 13 * 60
        try expect(Dates.quiet(now, settings: settings), "Same-day quiet window")
        try CoreChecks.rejects { _ = try Dates.parse("2026-10-24") }
        try expect(Dates.string(try Dates.parse("2026-10-24T12:00:00+03:30")) == "2026-10-24T08:30:00Z", "Offset conversion")
        passed.append("Date validation and quiet-hours boundaries in profile timezone")

        var legacyObject = try JSONSerialization.jsonObject(with: JSONEncoder().encode(Snapshot())) as! [String: Any]
        legacyObject.removeValue(forKey: "services")
        let legacy = try JSONDecoder().decode(Snapshot.self, from: JSONSerialization.data(withJSONObject: legacyObject))
        try expect(legacy.services == nil && legacy.notes.isEmpty, "Existing preview snapshots must decode without new service fields")
        passed.append("Backward-compatible migration from the original Apple preview")

        var state = Snapshot(); state.configuration = CoreChecks.configuration()
        let note = Note(text: "Remind me tomorrow at 9 to send the proposal. Meet Sara tomorrow. We will be at the office.")
        state.notes = [note]
        let device = FakeDevice()
        let future = Dates.string(Date().addingTimeInterval(86400))
        let reminderCall = call("set_reminder", ["message": "Send proposal", "when": future, "repeat": "DAILY", "evidence": "Remind me tomorrow at 9 to send the proposal."])
        _ = try await ServiceTools.execute(reminderCall, note: note, snapshot: &state, device: device)
        _ = try await ServiceTools.execute(reminderCall, note: note, snapshot: &state, device: device)
        try expect(state.services!.reminders.count == 1 && device.scheduled.count == 1, "Reminder retries must use a stable OS identifier")
        let reminder = state.services!.reminders[0]
        let next = Dates.nextFire(reminder, after: reminder.fireAt!.addingTimeInterval(1), zone: "Asia/Tehran")!
        try expect(next > reminder.fireAt!, "Repeating reminder must advance")
        passed.append("Timed/repeating reminders have stable retry identifiers and future recurrence")

        var denied = Snapshot()
        device.allowed = false
        do { _ = try await ServiceTools.execute(reminderCall, note: note, snapshot: &denied, device: device); throw CheckError.failed("Permission denial expected") }
        catch HerError.invalidTool {}
        try expect(denied.services!.reminders.isEmpty, "Denied permission must not claim a saved/scheduled notification")
        device.allowed = true
        let cancel = call("update_reminder", ["id": reminder.id, "status": "CANCELLED", "evidence": "Remind me tomorrow"])
        _ = try await ServiceTools.execute(cancel, note: note, snapshot: &state, device: device)
        try expect(state.services!.reminders[0].status == "CANCELLED" && device.scheduled[reminder.id] == nil, "Cancellation must reach device adapter")
        passed.append("Permission denial does not claim success; cancellation removes the native schedule")

        let context = call("set_context_reminder", ["message": "Ask about proposal", "person": "Sara", "evidence": "Meet Sara tomorrow."])
        _ = try await ServiceTools.execute(context, note: note, snapshot: &state, device: device)
        _ = try await ServiceTools.execute(context, note: note, snapshot: &state, device: device)
        let contextual = state.services!.reminders.first { $0.trigger == "PERSON" }!
        try expect(contextual.fireAt == nil && state.services!.reminders.filter { $0.trigger == "PERSON" }.count == 1 && contextual.personID != nil, "Context reminder must not invent a time")
        try expect(state.services!.syncRows["people/" + contextual.personID!]!["name"]?.text == "Sara", "Person reference must resolve before syncing")
        passed.append("Person reminders preserve source, valid person references and unknown time")

        do { _ = try await ServiceTools.execute(reminderCall, note: note, snapshot: &state, device: device, autonomous: true); throw CheckError.failed("Autonomous write must fail") }
        catch HerError.invalidTool {}
        let extra = call("set_reminder", ["message": "x", "when": future, "evidence": "Meet Sara", "confirm": "true"])
        do { _ = try await ServiceTools.execute(extra, note: note, snapshot: &state, device: device); throw CheckError.failed("Extra schema field must fail") }
        catch HerError.invalidTool {}
        passed.append("Service tools reject extra fields and all autonomous writes")

        let start = Date().addingTimeInterval(2 * 86400), end = start.addingTimeInterval(3600)
        let eventCall = call("create_calendar_event", ["title": "Meet Sara", "start": Dates.string(start), "end": Dates.string(end), "evidence": "Meet Sara tomorrow."])
        device.events = [.init(id: "busy", title: "Existing", start: start, end: end, location: nil, writable: true)]
        do { _ = try await ServiceTools.execute(eventCall, note: note, snapshot: &state, device: device); throw CheckError.failed("Overlap must fail") }
        catch HerError.invalidTool {}
        try expect(device.changes.isEmpty, "Overlap must not write")
        device.events = []
        _ = try await ServiceTools.execute(eventCall, note: note, snapshot: &state, device: device)
        _ = try await ServiceTools.execute(eventCall, note: note, snapshot: &state, device: device)
        try expect(device.changes.count == 1, "Calendar retry must replay persisted receipt")
        passed.append("Calendar conflicts reject writes and persisted receipts prevent duplicate creates")

        var recoveredState = Snapshot()
        device.events = [.init(id: "recovered-instance", title: "Meet Sara", start: device.changes[0].start!, end: device.changes[0].end!, location: nil, writable: true, writeReceipt: device.changes[0].journalID)]
        _ = try await ServiceTools.execute(eventCall, note: note, snapshot: &recoveredState, device: device)
        try expect(device.changes.count == 1 && recoveredState.services!.calendarJournal.values.contains("recovered-instance"), "A platform write that survived a disk failure must recover before overlap rejection")
        passed.append("Calendar receipt recovery survives a platform-success/local-persistence failure")

        device.events = [.init(id: "event-1", title: "Meet Sara", start: start, end: end, location: nil, writable: true)]
        let deletion = call("request_calendar_delete", ["id": "event-1", "evidence": "Meet Sara tomorrow."])
        _ = try await ServiceTools.execute(deletion, note: note, snapshot: &state, device: device)
        try expect(state.services!.pendingDeletes.count == 1 && device.changes.count == 1, "Model may request, never execute, external deletion")
        device.events[0].writable = false
        do { _ = try await ServiceTools.execute(deletion, note: note, snapshot: &state, device: device); throw CheckError.failed("Read-only calendar delete must fail") }
        catch HerError.invalidTool {}
        passed.append("External deletion waits for native confirmation; read-only Google events cannot be deleted")

        var internalState = Snapshot()
        let internalCall = call("create_calendar_event", ["title": "Internal meeting", "start": Dates.string(start), "end": Dates.string(end), "target": "INTERNAL", "evidence": "Meet Sara tomorrow."])
        _ = try await ServiceTools.execute(internalCall, note: note, snapshot: &internalState, device: nil)
        _ = try await ServiceTools.execute(internalCall, note: note, snapshot: &internalState, device: nil)
        try expect(internalState.services!.pendingOps.count == 1, "Internal calendar writes are idempotent and work without OS permission")
        var internalPeer = Snapshot()
        try SyncMerge.apply(internalState.services!.pendingOps, to: &internalPeer)
        let internalEvent = InternalCalendar.entries(internalPeer, from: start.addingTimeInterval(-1), to: end.addingTimeInterval(1)).first!
        try expect(internalEvent.title == "Internal meeting" && internalEvent.writable, "Android-compatible internal calendar projection")
        try InternalCalendar.write(id: String(internalEvent.id.dropFirst(9)), fields: [:], deleted: true, snapshot: &internalPeer)
        try expect(InternalCalendar.entries(internalPeer, from: start, to: end).isEmpty, "Internal deletion is a sync tombstone")
        passed.append("Internal calendar creation/restore/deletion works across devices without mirroring OS events")

        var conditionalState = Snapshot()
        let task = Record(kind: .task, text: "Send proposal", evidence: "send the proposal", sourceMessageID: note.id)
        conditionalState.records = [task]
        let conditionalCall = call("set_reminder", ["message": "Proposal still open", "when": future, "onlyIfType": "tasks", "onlyIfID": task.id.uuidString, "evidence": "send the proposal"])
        _ = try await ServiceTools.execute(conditionalCall, note: note, snapshot: &conditionalState, device: device)
        let conditionalID = conditionalState.services!.reminders[0].id
        _ = try await ServiceTools.execute(call("update_record", ["id": task.id.uuidString, "done": "true", "evidence": "send the proposal"]), note: note, snapshot: &conditionalState, device: device)
        try expect(conditionalState.records[0].done && device.scheduled[conditionalID] == nil, "Completing a condition target cancels its OS request")
        passed.append("Conditional reminders link to existing open records and cancel when completed")

        var forgetState = conditionalState
        try SyncJournal.capture(previous: Snapshot(), next: &forgetState, now: now)
        let beforeForget = forgetState
        forgetState.forget(forgetState.records[0])
        try SyncJournal.capture(previous: beforeForget, next: &forgetState, now: now.addingTimeInterval(1))
        try expect(forgetState.services!.reminders.isEmpty && forgetState.services!.pendingOps.contains(where: { $0.entityType == "reminders" && $0.opType == "DELETE" }), "Forgetting source-derived reminders must create sync tombstones")
        passed.append("Forgetting removes derived reminders and propagates their deletion")

        var oldConversation = Snapshot()
        oldConversation.services!.syncRows["chat_messages/old"] = ["id": .string("old"), "role": .string("ASSISTANT"), "content": .string("Private old quotation"), "createdAt": .number(1)]
        oldConversation.services!.contextFloor = now
        try expect(!(try SyncContext.encoded(oldConversation)).contains("Private old quotation"), "Old synced chat must not bypass the forgetting boundary")
        passed.append("Forgotten conversation cannot return to model context through synced chat copies")
        oldConversation.services!.syncRows["long_term_memories/root"] = ["id": .string("root"), "deletedAt": .number(now.timeIntervalSince1970 * 1000)]
        oldConversation.services!.syncRows["long_term_memories/late"] = ["id": .string("late"), "content": .string("Late private derivative"), "derivedFromJson": .string("[\"root\"]"), "updatedAt": .number(now.addingTimeInterval(100).timeIntervalSince1970 * 1000)]
        SyncMerge.project(into: &oldConversation)
        try expect(oldConversation.records.isEmpty && !(try SyncContext.encoded(oldConversation)).contains("Late private derivative"), "A newer derivative cannot bypass its deleted dependency")
        passed.append("Late-arriving memory descendants remain suppressed after source deletion")

        var follow = Snapshot(); follow.configuration = CoreChecks.configuration(); follow.services!.followUp.enabled = true
        follow.services!.followUp.timeZone = "Asia/Tehran"
        let due = FollowUpPolicy.due(state: follow.services!, now: now)
        try expect(due.contains(.hourly) && due.contains(.briefing) && due.contains(.nightly), "Catch-up should cover missed passes")
        try expect(FollowUpPolicy.due(state: follow.services!, now: now, lowBattery: true).isEmpty, "Low battery must skip LLM work")
        let quietTime = try Dates.parse("2026-10-24T23:45:00+03:30")
        try expect(!FollowUpPolicy.due(state: follow.services!, now: quietTime).contains(.hourly), "Quiet hours gate hourly passes")
        let silent = ScriptedClient([.success(.init(role: "assistant", content: "NO_NOTIFICATION"))])
        follow = try await FollowUpRunner.run(pass: .hourly, snapshot: follow, key: "", client: silent, device: device, now: now) { _ in }
        try expect(device.notices.isEmpty && follow.services!.lastHourly == now, "Successful silence stamps run without notifying")
        let malicious = ScriptedClient([.success(.init(role: "assistant", toolCalls: [eventCall]))])
        do { _ = try await FollowUpRunner.run(pass: .hourly, snapshot: follow, key: "", client: malicious, device: device, now: now) { _ in }; throw CheckError.failed("Autonomous proposed tool must not execute") }
        catch HerError.invalidResponse {}
        passed.append("Background policy: quiet hours, low battery, catch-up, successful silence and read-only enforcement")

        let noticeText = "A recorded proposal deadline is approaching."
        let notifier = ScriptedClient([.success(.init(role: "assistant", content: noticeText))])
        follow = try await FollowUpRunner.run(pass: .briefing, snapshot: follow, key: "", client: notifier, device: device, now: now) { _ in }
        try expect(device.notices.count == 1 && follow.latestReply == noticeText, "Delivered follow-up becomes latest message")
        try expect(!FollowUpPolicy.mayNotify(noticeText, state: follow.services!, now: now.addingTimeInterval(3600)), "Duplicate notice cooldown")
        try expect(!FollowUpPolicy.due(state: follow.services!, now: now).contains(.briefing), "At most one successful daily briefing")
        passed.append("Grounded follow-up delivery, latest-message continuity and duplicate suppression")
        let nightly = ScriptedClient([.success(.init(role: "assistant", content: "Private tentative digest of recorded open work."))])
        let noticesBeforeNight = device.notices.count
        follow = try await FollowUpRunner.run(pass: .nightly, snapshot: follow, key: "", client: nightly, device: device, now: now) { _ in }
        try expect(follow.services!.digestSummary != nil && device.notices.count == noticesBeforeNight, "Nightly digest is private, never a user notification")
        passed.append("Nightly review saves a tentative private digest without messaging the user")

        var source = Snapshot(); source.configuration = CoreChecks.configuration()
        source.configuration.userName = "Test user"
        let record = Record(kind: .task, text: "Renew passport", evidence: "Renew passport", sourceMessageID: UUID())
        source.records = [record]
        try SyncJournal.capture(previous: Snapshot(), next: &source, now: now)
        let wire = try source.services!.pendingOps.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) }.joined(separator: "\n")
        let parsed = try SyncMerge.parse(wire)
        try expect(parsed.first?.opType == "UPSERT" && parsed.contains(where: { $0.entityType == "tasks" }), "Android wire operation shape")
        try expect(!wire.contains("apiKey") && !wire.contains("test-model"), "Model credentials/settings must not sync")
        var target = Snapshot()
        try SyncMerge.apply(parsed, to: &target)
        try expect(target.records.first?.text == "Renew passport" && target.services!.pendingOps.isEmpty, "Remote task projection must not echo")
        let before = target
        target.notes.append(Note(text: "A new local note"))
        try SyncJournal.capture(previous: before, next: &target, now: now.addingTimeInterval(100))
        try expect(!target.services!.pendingOps.contains(where: { $0.entityType == "tasks" }), "Unchanged imported records must not reupload")
        passed.append("Android-compatible NDJSON, credential exclusion, task projection and no-echo journaling")

        var enumState = Snapshot()
        var waiting = Record(kind: .waiting, text: "Waiting on Sara", evidence: "Waiting on Sara", sourceMessageID: UUID()); waiting.done = true
        let memory = Record(kind: .memory, text: "Prefers short replies", evidence: "Short replies", sourceMessageID: UUID())
        enumState.records = [waiting, memory]
        try SyncJournal.capture(previous: Snapshot(), next: &enumState, now: now)
        try expect(enumState.services!.syncRows["open_loops/" + waiting.id.uuidString]?["status"]?.text == "CLOSED", "Android OpenLoopStatus uses CLOSED, not RESOLVED")
        try expect(enumState.services!.syncRows["long_term_memories/" + memory.id.uuidString]?["source"]?.text == "USER_EXPLICIT", "Android MemorySource uses USER_EXPLICIT")
        passed.append("Wire enums match Android CLOSED and USER_EXPLICIT exactly")

        let base: SyncRow = ["id": .string("rice"), "name": .string("Rice"), "status": .string("ACTIVE"), "updatedAt": .number(100), "deviceId": .string("a"), "unknownField": .string("retain")]
        var purchased = base; purchased["status"] = .string("PURCHASED"); purchased["deviceId"] = .string("b")
        let m1 = SyncMerge.merge(existing: base, incoming: purchased, type: "groceries", device: "b")
        let m2 = SyncMerge.merge(existing: purchased, incoming: base, type: "groceries", device: "a")
        try expect(m1["status"]?.text == "PURCHASED" && m2["status"]?.text == "PURCHASED", "Concurrent purchase must win")
        var deleted = base; deleted["deletedAt"] = .number(200)
        var later = purchased; later["updatedAt"] = .number(300)
        try expect(SyncMerge.merge(existing: deleted, incoming: later, type: "groceries", device: "z")["deletedAt"]?.number == 200, "Tombstones prevent resurrection")
        passed.append("Merge conflicts preserve concurrent purchases, opaque fields and sticky deletion")

        var remoteState = ServiceState(); remoteState.deviceID = "android"
        let pendingRow: SyncRow = ["id": .string("android-user-note"), "role": .string("USER"), "content": .string("Remote pending"), "status": .string("PENDING"), "deviceId": .string("android"), "createdAt": .number(1000), "updatedAt": .number(1000)]
        let remotePending = try SyncOperation(type: "chat_messages", entityID: "android-user-note", payload: pendingRow, deleted: false, state: &remoteState, now: now)
        let learning: SyncRow = ["id": .string("path:python"), "contentJson": .string("{\"lesson\":\"opaque\"}"), "updatedAt": .number(1000), "deviceId": .string("android")]
        let opaque = try SyncOperation(type: "learning_records", entityID: "path:python", payload: learning, deleted: false, state: &remoteState, now: now)
        let system: SyncRow = ["id": .string("sys1"), "source": .string("SYSTEM"), "title": .string("Private system mirror")]
        let mirror = try SyncOperation(type: "calendar_events", entityID: "sys1", payload: system, deleted: false, state: &remoteState, now: now)
        var remote = Snapshot()
        try SyncMerge.apply([remotePending, opaque, mirror], to: &remote)
        try expect(remote.notes.first?.remote == true && remote.notes.first?.reply == nil, "Remote pending note must be history only")
        try expect(remote.services!.syncRows["learning_records/path:python"]?["contentJson"] == learning["contentJson"], "Opaque domains retained")
        try expect(remote.services!.syncRows["calendar_events/sys1"] == nil, "System mirrors must stay device-local")
        passed.append("Remote pending messages never enter outbox; opaque Android domains survive and system mirrors stay local")

        let drive = FakeDrive()
        var disk = source
        let synced = try await DriveSync.run(snapshot: disk, token: "mock", drive: drive) { disk = $0 }
        try expect(synced.services!.pendingOps.isEmpty && drive.uploads.count == 1, "Successful upload acknowledges durable ops")
        let upload = drive.uploads[0]
        try expect(ChangeLogName(upload.name)?.last == source.services!.pendingOps.last?.seq, "Android filename sequence range")
        let peer = FakeDrive()
        peer.files = [DriveFile(id: "1", name: upload.name)]
        peer.downloads["1"] = upload.content
        var received = Snapshot()
        received = try await DriveSync.run(snapshot: received, token: "mock", drive: peer) { _ in }
        try expect(received.records.first?.text == "Renew passport" && received.services!.pendingOps.isEmpty, "Peer download does not echo")
        let count = peer.downloadCount
        _ = try await DriveSync.run(snapshot: received, token: "mock", drive: peer) { _ in }
        try expect(peer.downloadCount == count, "Cursor avoids redownload")
        passed.append("Transport integration: upload acknowledgement, Android filenames, peer restore and per-device cursors")

        let corrupt = FakeDrive(); corrupt.files = [DriveFile(id: "bad", name: "android/1-2.ndjson")]; corrupt.downloads["bad"] = "not JSON"
        var untouched = Snapshot()
        do { _ = try await DriveSync.run(snapshot: untouched, token: "", drive: corrupt) { untouched = $0 }; throw CheckError.failed("Corrupt log must fail") }
        catch is DecodingError {}
        try expect(untouched.services!.cursors["android"] == nil, "Corrupt log must not advance cursor")
        let offline = FakeDrive(); offline.failUpload = true
        var retryDisk = source
        do { _ = try await DriveSync.run(snapshot: retryDisk, token: "", drive: offline) { retryDisk = $0 }; throw CheckError.failed("Offline upload must fail") }
        catch HerError.http(503) {}
        try expect(retryDisk.services!.pendingOps == source.services!.pendingOps, "Upload failures retain pending operations")
        passed.append("Sync failures retain pending writes and never advance a corrupt-log cursor")
        return passed
    }
}

@MainActor final class FakeDevice: DeviceServices {
    var events: [CalendarEntry] = []
    var changes: [CalendarChange] = []
    var scheduled: [String: ReminderItem] = [:]
    var notices: [String] = []
    var allowed = true
    func calendarEntries(from: Date, to: Date) async throws -> [CalendarEntry] { events }
    func changeCalendar(_ change: CalendarChange) async throws -> String { changes.append(change); return "event-\(changes.count)" }
    func schedule(_ reminder: ReminderItem) async throws {
        guard allowed else { throw HerError.invalidTool("Permission denied") }
        scheduled[reminder.id] = reminder
    }
    func cancelReminder(id: String) { scheduled.removeValue(forKey: id) }
    func publishNotice(id: String, text: String) async throws { notices.append(text) }
}

final class FakeDrive: DriveTransport {
    var files: [DriveFile] = []
    var downloads: [String: String] = [:]
    var uploads: [(name: String, content: String)] = []
    var downloadCount = 0
    var failUpload = false
    func list(token: String) async throws -> [DriveFile] { files }
    func download(token: String, id: String) async throws -> String { downloadCount += 1; return downloads[id]! }
    func upload(token: String, name: String, content: String) async throws {
        if failUpload { throw HerError.http(503) }
        uploads.append((name, content))
    }
}
