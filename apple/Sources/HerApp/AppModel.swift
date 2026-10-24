import Foundation
import SwiftUI
import HerCore

@MainActor final class AppModel: ObservableObject {
    @Published var snapshot = Snapshot()
    @Published var busy = false
    @Published var error: String?
    @Published var available = false
    @Published var persian = UserDefaults.standard.object(forKey: "HerPersian") as? Bool ?? true {
        didSet { UserDefaults.standard.set(persian, forKey: "HerPersian") }
    }
    private var store: LocalStore?
    private var activeTask: Task<Void, Never>?
    var pending: [Note] { snapshot.notes.filter { $0.reply == nil } }
    var configured: Bool { (try? snapshot.configuration.endpoint()) != nil }

    init() {
        do {
            let store = try LocalStore.applicationStore()
            snapshot = try store.load()
            self.store = store; available = true
        } catch {
            self.error = "Local data could not be loaded. It has not been overwritten. \(error.localizedDescription)"
        }
    }
    func t(_ english: String, _ farsi: String) -> String { persian ? farsi : english }
    func save(_ value: Snapshot) throws {
        guard let store, available else { throw HerError.storage("Local storage is unavailable.") }
        try store.save(value)
        snapshot = value
    }
    func configure(_ config: Configuration, key: String) throws {
        _ = try config.endpoint()
        guard !config.userName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !config.assistantName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw HerError.invalidTool(t("Enter both names.", "هر دو نام را وارد کنید."))
        }
        let oldKey = try CredentialStore.read()
        try CredentialStore.write(key.trimmingCharacters(in: .whitespacesAndNewlines))
        var value = snapshot; value.configuration = config
        do { try save(value) } catch { try? CredentialStore.write(oldKey); throw error }
    }
    func enqueue(_ text: String) -> Bool {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= 16000, !busy, available else { return false }
        do {
            var value = snapshot
            value.notes.append(Note(text: text))
            try save(value)
            processQueue()
            return true
        } catch { self.error = error.localizedDescription; return false }
    }
    func processQueue() {
        guard !busy, available, configured, !pending.isEmpty else { return }
        busy = true; error = nil
        activeTask = Task {
            defer { busy = false; activeTask = nil }
            do {
                let key = try CredentialStore.read()
                while let note = pending.first {
                    try Task.checkCancellation()
                    _ = try await Agent.respond(note: note, snapshot: snapshot, key: key, client: OpenAIClient(), persist: { [self] in try save($0) })
                }
            } catch is CancellationError {
                error = t("Stopped. Unfinished notes remain queued.", "متوقف شد. پیام‌های ناتمام در صف باقی ماندند.")
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
    func stop() { activeTask?.cancel() }
    func complete(_ record: Record) {
        guard !busy else { return }
        var value = snapshot
        guard let index = value.records.firstIndex(where: { $0.id == record.id }) else { return }
        value.records[index].done.toggle()
        do { try save(value) } catch { self.error = error.localizedDescription }
    }
    func delete(_ record: Record) {
        guard !busy else { return }
        var value = snapshot
        value.forget(record)
        do { try save(value) } catch { self.error = error.localizedDescription }
    }
    func discard(_ note: Note) {
        guard !busy else { return }
        var value = snapshot; value.notes.removeAll { $0.id == note.id && $0.reply == nil }
        do { try save(value) } catch { self.error = error.localizedDescription }
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
