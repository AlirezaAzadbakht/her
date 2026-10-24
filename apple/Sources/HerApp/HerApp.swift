import SwiftUI
import HerCore
#if os(macOS)
import AppKit
#endif

@main struct HerAppleApp: App {
    @StateObject private var model = AppModel.shared
    @Environment(\.scenePhase) private var scenePhase
    init() {
        AppModel.shared.background.register()
        #if os(macOS)
        if ProcessInfo.processInfo.arguments.contains("--background-once") { NSApp.setActivationPolicy(.prohibited) }
        #endif
    }
    var body: some Scene {
        WindowGroup("Her") {
            RootView().environmentObject(model)
                .environment(\.layoutDirection, model.persian ? .rightToLeft : .leftToRight)
                .onOpenURL { model.google.handle($0) }
                .task {
                    #if os(macOS)
                    if ProcessInfo.processInfo.arguments.contains("--background-once") {
                        NSApp.windows.forEach { $0.orderOut(nil) }
                        do { try await model.wakeup() } catch { /* Retry on the next scheduled launch. */ }
                        NSApp.terminate(nil)
                    }
                    #endif
                    do { try await model.wakeup() } catch { model.error = error.localizedDescription }
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { Task { do { try await model.wakeup() } catch { model.error = error.localizedDescription } } }
                    if phase == .background { model.background.schedule(enabled: model.shouldWake) }
                }
                #if os(macOS)
                .frame(minWidth: 520, minHeight: 640)
                .onAppear {
                    if !ProcessInfo.processInfo.arguments.contains("--background-once") { NSApp.setActivationPolicy(.regular); NSApp.activate(ignoringOtherApps: true) }
                }
                #endif
        }
        #if os(macOS)
        .defaultSize(width: 680, height: 820)
        #endif
    }
}

private struct RootView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        TabView {
            ConversationView().tabItem { Label(model.t("Her", "گفت‌وگو"), systemImage: "bubble.left") }
            MemoryView().tabItem { Label(model.t("Memory", "حافظه"), systemImage: "tray.full") }
            CalendarView().tabItem { Label(model.t("Calendar", "تقویم"), systemImage: "calendar") }
            RemindersView().tabItem { Label(model.t("Reminders", "یادآورها"), systemImage: "bell") }
            SettingsView().tabItem { Label(model.t("Settings", "تنظیمات"), systemImage: "gearshape") }
        }
        .tint(.primary)
    }
}

private struct ConversationView: View {
    @EnvironmentObject private var model: AppModel
    @State private var draft = ""
    @State private var showQueue = false
    @FocusState private var focused: Bool
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(model.snapshot.configuration.assistantName.isEmpty ? "Her" : model.snapshot.configuration.assistantName)
                        .font(.title2.weight(.semibold))
                    Text(model.t("APPLE · EXPERIMENTAL", "نسخهٔ آزمایشی Apple"))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if !model.pending.isEmpty {
                    Button { showQueue = true } label: {
                        Label("\(model.pending.count)", systemImage: "tray.and.arrow.up")
                            .frame(minWidth: 44, minHeight: 44)
                    }.accessibilityLabel(model.t("Queued notes", "پیام‌های در صف"))
                }
            }.padding(24)
            ScrollView {
                VStack(spacing: 24) {
                    ZStack {
                        Circle().stroke(.primary.opacity(0.06), lineWidth: 1).frame(width: 144, height: 144)
                        Circle().stroke(.primary.opacity(0.1), lineWidth: 1).frame(width: 112, height: 112)
                        Circle().fill(.primary.opacity(0.08)).frame(width: 80, height: 80)
                        Circle().fill(.primary.opacity(0.8)).frame(width: 18, height: 18)
                    }.accessibilityHidden(true).padding(.top, 24)
                    if model.busy {
                        ProgressView(model.t("Thinking…", "در حال پردازش…"))
                    }
                    Text(model.snapshot.latestReply.isEmpty
                         ? model.t("One conversation. Start with what is on your mind.", "یک گفت‌وگوی مداوم. از چیزی بنویسید که ذهنتان را مشغول کرده است.")
                         : model.snapshot.latestReply)
                        .font(.title3).lineSpacing(8).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if !model.configured {
                        Label(model.t("Set your provider and model in Settings first.", "ابتدا سرویس مدل و کلید دسترسی را در تنظیمات وارد کنید."), systemImage: "key")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                    Text(model.t("Calendar and reminders use system permissions. Enable automatic review and Google sync in Settings.", "تقویم و یادآورها به مجوز سیستم نیاز دارند. بازبینی خودکار و همگام‌سازی Google را از تنظیمات فعال کنید."))
                        .font(.caption).foregroundStyle(.secondary)
                }.frame(maxWidth: 580).padding(.horizontal, 24).padding(.bottom, 24)
                    .frame(maxWidth: .infinity)
            }
            if let error = model.error {
                Text(error).font(.callout).foregroundStyle(.red).textSelection(.enabled)
                    .frame(maxWidth: 580, alignment: .leading).padding(.horizontal, 24).padding(.vertical, 8)
                    .accessibilityLabel(model.t("Error: ", "خطا: ") + error)
            }
            Divider()
            VStack(alignment: .leading, spacing: 8) {
                Text(model.t("Your note", "پیام شما")).font(.caption).foregroundStyle(.secondary)
                TextEditor(text: $draft).font(.body).frame(minHeight: 72, maxHeight: 132)
                    .focused($focused).scrollContentBackground(.hidden)
                    .environment(\.layoutDirection, draft.contains(where: { ("\u{0600}"..."\u{06FF}").contains(String($0)) }) ? .rightToLeft : .leftToRight)
                    .accessibilityLabel(model.t("Your note", "پیام شما"))
                HStack {
                    Text(model.t("Sent to your chosen AI provider", "متن برای سرویس مدل انتخابی شما ارسال می‌شود"))
                        .font(.caption2).foregroundStyle(.secondary)
                    Spacer()
                    if model.busy {
                        Button(model.t("Stop", "توقف")) { model.stop() }.frame(minHeight: 44)
                    } else {
                        Button { if model.enqueue(draft) { draft = "" } } label: {
                            Label(model.t("Send", "ارسال"), systemImage: "arrow.up").frame(minHeight: 32)
                        }.buttonStyle(.borderedProminent).keyboardShortcut(.return, modifiers: .command)
                            .disabled(!model.available || !model.configured || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || draft.count > 16000)
                    }
                }
            }.padding(20).frame(maxWidth: 640).frame(maxWidth: .infinity)
        }.sheet(isPresented: $showQueue) { QueueView().environmentObject(model) }
    }
}

private struct MemoryView: View {
    @EnvironmentObject private var model: AppModel
    @State private var filter = ""
    @State private var deleting: Record?
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(model.t("Saved from your words. Source quotations are kept with each record. Deleting a record also removes its source note from the recent conversation context.", "اطلاعات ثبت‌شده از گفته‌های شما. متن شاهد کنار هر مورد حفظ می‌شود. با حذف یک مورد، پیام منبع آن نیز از زمینهٔ اخیر گفت‌وگو حذف می‌شود."))
                        .font(.callout).foregroundStyle(.secondary)
                }
                ForEach(model.snapshot.records.reversed().filter { filter.isEmpty || $0.text.localizedCaseInsensitiveContains(filter) || $0.evidence.localizedCaseInsensitiveContains(filter) }) { record in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(model.kindName(record.kind)).font(.caption).foregroundStyle(.secondary)
                            Spacer()
                            if record.done { Label(model.t("Done", "انجام‌شده"), systemImage: "checkmark").font(.caption) }
                        }
                        Text(record.text).textSelection(.enabled)
                        DisclosureGroup(model.t("Source", "متن شاهد")) {
                            Text(record.evidence).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                            Text(record.createdAt, style: .date).font(.caption2)
                        }
                        HStack {
                            if [.task, .commitment, .grocery, .waiting].contains(record.kind) {
                                Button(record.done ? model.t("Reopen", "بازگشایی") : model.t("Mark done", "انجام شد")) { model.complete(record) }
                                    .frame(minHeight: 44)
                            }
                            Spacer()
                            Button(role: .destructive) { deleting = record } label: {
                                Label(model.t("Delete", "حذف"), systemImage: "trash").frame(minHeight: 44)
                            }
                        }.buttonStyle(.borderless).disabled(model.busy)
                    }.padding(.vertical, 8)
                }
                if model.snapshot.records.isEmpty {
                    Text(model.t("No saved records yet.", "هنوز موردی ثبت نشده است.")).foregroundStyle(.secondary)
                }
            }.searchable(text: $filter, prompt: model.t("Search memory", "جست‌وجو در حافظه"))
                .navigationTitle(model.t("Memory", "حافظه"))
                .alert(model.t("Delete this record?", "این مورد حذف شود؟"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
                    Button(model.t("Delete", "حذف"), role: .destructive) { if let deleting { model.delete(deleting) }; deleting = nil }
                    Button(model.t("Cancel", "انصراف"), role: .cancel) { deleting = nil }
                } message: { Text(model.t("Its source note, all records from that note, and the recent conversation context will be removed. Other saved records and unrelated queued notes remain. This cannot be undone.", "پیام منبع، تمام موارد ثبت‌شده از آن پیام و زمینهٔ اخیر گفت‌وگو پاک می‌شوند. سایر موارد حافظه و پیام‌های در صف باقی می‌مانند. این عمل برگشت‌پذیر نیست.")) }
        }
    }
}

private struct QueueView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var deleting: Note?
    var body: some View {
        NavigationStack {
            List {
                Text(model.t("Notes are processed in order when you send or retry. No automatic background delivery. A retry may incur another provider charge.", "پیام‌ها با ارسال یا تلاش دوباره، به‌ترتیب پردازش می‌شوند. ارسال خودکار پس‌زمینه فعال نیست. تلاش مجدد ممکن است هزینهٔ مدل داشته باشد."))
                    .font(.callout).foregroundStyle(.secondary)
                ForEach(model.pending) { note in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(note.text).textSelection(.enabled)
                        Text(note.createdAt, style: .date).font(.caption)
                        Button(model.t("Discard", "حذف از صف"), role: .destructive) { deleting = note }.disabled(model.busy)
                    }
                }
            }.navigationTitle(model.t("Queued notes", "صف پیام‌ها"))
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button(model.t("Close", "بستن")) { dismiss() } }
                    ToolbarItem(placement: .primaryAction) { Button(model.t("Retry", "تلاش دوباره")) { model.processQueue() }.disabled(model.busy || model.pending.isEmpty) }
                }
                .alert(model.t("Discard note?", "پیام از صف حذف شود؟"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
                    Button(model.t("Discard", "حذف"), role: .destructive) { if let deleting { model.discard(deleting) }; deleting = nil }
                    Button(model.t("Cancel", "انصراف"), role: .cancel) { deleting = nil }
                } message: { Text(model.t("Any records already saved during an earlier attempt remain in Memory.", "مواردی که در تلاش قبلی ثبت شده‌اند در حافظه باقی می‌مانند.")) }
        }.frame(minWidth: 300, minHeight: 380)
    }
}

private struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var config = Configuration()
    @State private var key = ""
    @State private var status = ""
    @State private var canSave = false
    var body: some View {
        NavigationStack {
            Form {
                Section(model.t("Language", "زبان")) { Toggle("فارسی", isOn: $model.persian) }
                Section(model.t("Names", "نام‌ها")) {
                    TextField(model.t("Your name", "نام شما"), text: $config.userName)
                    TextField(model.t("Assistant name", "نام دستیار"), text: $config.assistantName)
                }
                Section(model.t("AI provider", "سرویس مدل")) {
                    TextField("Base URL · https://…/v1", text: $config.baseURL)
                        .autocorrectionDisabled().environment(\.layoutDirection, .leftToRight)
                    TextField("Model", text: $config.model).autocorrectionDisabled().environment(\.layoutDirection, .leftToRight)
                    SecureField("API key", text: $key).autocorrectionDisabled().environment(\.layoutDirection, .leftToRight)
                    Text(model.t("Use an OpenAI-compatible provider with tool calling. Include /v1 if required by your provider. Keys stay in this device’s Keychain. Local notes, recent conversation and relevant memories are sent to the provider when you chat.", "سرویسی سازگار با OpenAI و دارای قابلیت فراخوانی ابزار انتخاب کنید. در صورت نیاز سرویس، مسیر /v1 را وارد کنید. کلید در Keychain همین دستگاه می‌ماند. هنگام گفت‌وگو، پیام‌ها، زمینهٔ اخیر و حافظهٔ مرتبط برای آن سرویس ارسال می‌شوند."))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section {
                    Button(model.t("Save settings", "ذخیرهٔ تنظیمات")) {
                        do {
                            try model.configure(config, key: key)
                            status = model.t("Saved. Send a note to test the connection.", "ذخیره شد. برای آزمون اتصال، یک پیام ارسال کنید.")
                        } catch { status = error.localizedDescription }
                    }.disabled(!model.available || model.busy || !canSave).frame(minHeight: 44)
                    if !status.isEmpty { Text(status).font(.callout).textSelection(.enabled) }
                }
                IntegrationsSettings()
                Section(model.t("Data and platform limits", "داده و محدودیت‌های سیستم")) {
                    Text(model.t("Timed reminders run through system notifications without the model or network. iOS chooses when background reviews/sync run; exact hourly execution is not guaranteed. Google sync requires a maintainer-configured OAuth build. Android learning, emotional and executive-delegation engines are not yet ported. Local records are not app-encrypted and are excluded from backup. Use device encryption. No analytics are included.", "یادآور زمان‌دار را سیستم بدون مدل و اینترنت اجرا می‌کند. زمان اجرای بازبینی و همگام‌سازی پس‌زمینه در iOS را سیستم تعیین می‌کند؛ اجرای دقیق ساعتی تضمین نمی‌شود. اتصال Google به نسخه‌ای با شناسهٔ OAuth ثبت‌شده توسط صاحب پروژه نیاز دارد. موتورهای یادگیری، احساسات و واگذاری اجرایی Android هنوز منتقل نشده‌اند. فایل محلی در خود برنامه رمزگذاری نمی‌شود و در پشتیبان قرار نمی‌گیرد. از رمزگذاری دستگاه استفاده کنید. ابزار تحلیل رفتار کاربر در این نسخه وجود ندارد."))
                        .font(.callout).foregroundStyle(.secondary)
                }
            }.formStyle(.grouped).navigationTitle(model.t("Settings", "تنظیمات"))
                .safeAreaInset(edge: .bottom) { if let error = model.error { Text(error).font(.callout).foregroundStyle(.red).textSelection(.enabled).padding() } }
                .onAppear {
                    config = model.snapshot.configuration
                    do { key = try CredentialStore.read(); canSave = true }
                    catch { status = error.localizedDescription; canSave = false }
                }
        }
    }
}
