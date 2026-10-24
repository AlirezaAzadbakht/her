import SwiftUI
import HerCore

struct CalendarView: View {
    @EnvironmentObject private var model: AppModel
    @State private var deleting: PendingCalendarDelete?
    var body: some View {
        NavigationStack {
            List {
                Section {
                    DatePicker(model.t("Week starting", "شروع هفته"), selection: $model.calendarFrom, displayedComponents: .date)
                    HStack {
                        Button(model.t("Allow system Calendar", "اجازهٔ دسترسی به تقویم")) {
                            Task { do { try await model.device.requestCalendar(); await model.loadCalendar() } catch { model.error = error.localizedDescription } }
                        }
                        Spacer()
                        Button(model.t("Refresh", "بازخوانی")) { Task { await model.loadCalendar() } }
                    }.frame(minHeight: 44)
                    Text(model.t("Ask in chat to create or move events. Google entries are read-only. Deleting affects only the selected occurrence, after separate confirmation.", "برای ایجاد یا جابه‌جایی رویداد، در گفت‌وگو درخواست کنید. رویدادهای Google فقط خواندنی‌اند. حذف فقط بر همان نوبت رویداد اثر می‌گذارد و تأیید جداگانه می‌خواهد."))
                        .font(.caption).foregroundStyle(.secondary)
                }
                if let requests = model.snapshot.services?.pendingDeletes, !requests.isEmpty {
                    Section(model.t("Pending deletions", "حذف‌های منتظر تأیید")) {
                        ForEach(requests) { request in
                            VStack(alignment: .leading) {
                                Text(request.title)
                                if let date = request.start { Text(date.formatted()).font(.caption) }
                                HStack {
                                    Button(model.t("Confirm deletion…", "تأیید حذف…"), role: .destructive) { deleting = request }
                                    Spacer()
                                    Button(model.t("Keep event", "نگه‌داشتن رویداد")) { model.dismissDelete(request) }
                                }.buttonStyle(.borderless).frame(minHeight: 44).disabled(model.busy)
                            }
                        }
                    }
                }
                ForEach(model.calendarEvents) { event in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(event.title).font(.headline).textSelection(.enabled)
                        Text(event.start.formatted(date: .abbreviated, time: .shortened) + " – " + event.end.formatted(date: .omitted, time: .shortened))
                        if let location = event.location { Text(location).font(.callout) }
                        Text(event.id.hasPrefix("google:") ? "Google · " + model.t("Read only", "فقط خواندنی") : (event.id.hasPrefix("internal:") ? model.t("Synced internal calendar", "تقویم داخلی همگام‌شونده") : model.t("System calendar", "تقویم سیستم")))
                            .font(.caption).foregroundStyle(.secondary)
                    }.padding(.vertical, 8)
                }
                if model.calendarEvents.isEmpty { Text(model.t("No events loaded for this week. Check access before assuming the week is empty.", "رویدادی برای این هفته بارگذاری نشده است. پیش از خالی فرض‌کردن تقویم، دسترسی را بررسی کنید.")).foregroundStyle(.secondary) }
                if let error = model.error { Text(error).foregroundStyle(.red).textSelection(.enabled) }
            }.navigationTitle(model.t("Calendar", "تقویم"))
                .onChange(of: model.calendarFrom) { _, _ in Task { await model.loadCalendar() } }
                .alert(model.t("Delete this occurrence?", "این نوبت رویداد حذف شود؟"), isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
                    Button(model.t("Delete", "حذف"), role: .destructive) { if let request = deleting { Task { await model.confirmDelete(request) } }; deleting = nil }
                    Button(model.t("Cancel", "انصراف"), role: .cancel) { deleting = nil }
                } message: { Text((deleting?.title ?? "") + "\n" + (deleting?.start?.formatted() ?? "")) }
        }
    }
}

struct RemindersView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(model.t("Ask naturally in chat, for example: remind me tomorrow at 9 to send the proposal. Clock reminders are delivered by the OS. Only the creating device schedules them; changes from another device take effect after the creator syncs.", "در گفت‌وگو درخواست کنید؛ مثلاً: فردا ساعت ۹ یادآوری کن پیشنهاد را بفرستم. یادآور زمان‌دار را سیستم اجرا می‌کند. فقط دستگاه سازنده آن را زمان‌بندی می‌کند؛ تغییر از دستگاه دیگر پس از همگام‌سازی دستگاه سازنده اعمال می‌شود."))
                        .font(.callout).foregroundStyle(.secondary)
                    Button(model.t("Allow notifications", "اجازهٔ اعلان‌ها")) {
                        Task { do { try await model.device.requestNotifications() } catch { model.error = error.localizedDescription } }
                    }.frame(minHeight: 44)
                }
                ForEach((model.snapshot.services?.reminders ?? []).sorted { ($0.fireAt ?? .distantFuture) < ($1.fireAt ?? .distantFuture) }) { reminder in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(reminder.message).font(.headline).textSelection(.enabled)
                        if let date = Dates.nextFire(reminder, after: Date(), zone: reminder.timeZone ?? TimeZone.current.identifier) ?? reminder.fireAt { Text(date, format: .dateTime.year().month().day().hour().minute()) }
                        Text(reminder.status + " · " + reminder.repeatRule).font(.caption).foregroundStyle(.secondary)
                        Text(reminder.deviceID == model.snapshot.services?.deviceID ? model.t("Delivered by this device", "دستگاه مسئول: همین دستگاه") : model.t("Delivered by the creating device after sync", "دستگاه مسئول: دستگاه سازنده، پس از همگام‌سازی"))
                            .font(.caption).foregroundStyle(.secondary)
                        if reminder.trigger != "TIME" { Text(model.t("Context reminder; not a timed notification.", "یادآور وابسته به موقعیت است؛ اعلان زمان‌دار ندارد.")).font(.caption) }
                        if reminder.status == "SCHEDULED" {
                            Button(model.t("Cancel reminder", "لغو یادآور"), role: .destructive) { model.cancelReminder(reminder) }.disabled(model.busy).frame(minHeight: 44)
                        }
                    }.padding(.vertical, 8)
                }
                if model.snapshot.services?.reminders.isEmpty != false { Text(model.t("No reminders saved.", "یادآوری ثبت نشده است.")).foregroundStyle(.secondary) }
                if let error = model.error { Text(error).foregroundStyle(.red).textSelection(.enabled) }
                if !model.serviceStatus.isEmpty { Text(model.serviceStatus).font(.caption).textSelection(.enabled) }
            }.navigationTitle(model.t("Reminders", "یادآورها"))
        }
    }
}

struct IntegrationsSettings: View {
    @EnvironmentObject private var model: AppModel
    @State private var followUp = FollowUpSettings()
    @State private var readGoogleCalendar = false
    @State private var quietFrom = Date()
    @State private var quietTo = Date()
    var body: some View {
        Group {
            Section(model.t("Automatic review", "بازبینی خودکار")) {
                Toggle(model.t("Enable automatic follow-ups", "فعال‌کردن پیگیری خودکار"), isOn: $followUp.enabled)
                Toggle(model.t("Allow hourly review during quiet hours", "اجازهٔ بازبینی ساعتی در ساعات سکوت"), isOn: $followUp.hourlyDuringQuiet)
                DatePicker(model.t("Quiet from", "سکوت از"), selection: $quietFrom, displayedComponents: .hourAndMinute)
                DatePicker(model.t("Quiet until", "سکوت تا"), selection: $quietTo, displayedComponents: .hourAndMinute)
                Button(model.t("Save review settings", "ذخیرهٔ تنظیمات پیگیری")) {
                    let c = Calendar.current
                    followUp.quietStart = c.component(.hour, from: quietFrom) * 60 + c.component(.minute, from: quietFrom)
                    followUp.quietEnd = c.component(.hour, from: quietTo) * 60 + c.component(.minute, from: quietTo)
                    followUp.timeZone = TimeZone.current.identifier
                    Task { await model.updateSettings(followUp) }
                }.disabled(model.busy).frame(minHeight: 44)
                Text(model.t("Hourly review is usually silent. A morning briefing runs after quiet hours. Missed runs catch up on the next allowed wake; exact iOS timing is not guaranteed. Each review can use your provider credit.", "بازبینی ساعتی معمولاً ساکت است. خلاصهٔ صبح پس از ساعات سکوت اجرا می‌شود. نوبت‌های ازدست‌رفته در بیدارباش مجاز بعدی بررسی می‌شوند؛ زمان دقیق در iOS تضمین نمی‌شود. هر بازبینی ممکن است از اعتبار سرویس مدل استفاده کند."))
                    .font(.caption).foregroundStyle(.secondary)
                Text(model.t("After the first unlock following a restart, the model key can be used for permitted background work while iPhone is locked. It stays on this device. Nightly review keeps a private tentative digest rather than sending a notification.", "پس از اولین بازشدن قفل بعد از راه‌اندازی گوشی، کلید مدل برای کار مجاز پس‌زمینه هنگام قفل‌بودن آیفون هم قابل استفاده است و روی همین دستگاه می‌ماند. بازبینی شبانه خلاصه‌ای خصوصی و غیرقطعی نگه می‌دارد و اعلان نمی‌فرستد."))
                    .font(.caption).foregroundStyle(.secondary)
                #if os(macOS)
                Button(model.background.launchAgentInstalled ? model.t("Disable closed-app background work", "غیرفعال‌کردن اجرا هنگام بسته‌بودن برنامه") : model.t("Enable closed-app background work", "فعال‌کردن اجرا هنگام بسته‌بودن برنامه")) {
                    do { try model.background.setLaunchAgent(!model.background.launchAgentInstalled); model.serviceStatus = model.t("Login background setting updated", "تنظیم اجرای پس‌زمینه به‌روز شد") } catch { model.error = error.localizedDescription }
                }.disabled(model.busy).frame(minHeight: 44)
                Text(model.t("Installs a login job for this app's current path. Keep Her.app in a stable location; move it before enabling. The Mac must be awake and your session unlocked for Keychain/model access.", "یک کار پس‌زمینه برای مسیر فعلی برنامه در ورود به حساب مک ثبت می‌شود. پیش از فعال‌کردن، Her.app را در محل ثابتی قرار دهید. برای دسترسی به Keychain و مدل، مک باید بیدار و نشست باز باشد."))
                    .font(.caption).foregroundStyle(.secondary)
                #endif
            }
            Section(model.t("Google sync and calendar", "همگام‌سازی و تقویم Google")) {
                Toggle(model.t("Read Google Calendar", "خواندن تقویم Google"), isOn: $readGoogleCalendar)
                if model.snapshot.services?.syncEnabled == true {
                    Label(model.t("Google connected", "Google متصل است"), systemImage: "checkmark.circle")
                    if let date = model.snapshot.services?.lastSync { Text(model.t("Last sync: ", "آخرین همگام‌سازی: ") + date.formatted()).font(.caption) }
                    Button(model.t("Disconnect Google", "قطع اتصال Google")) { model.disconnectGoogle() }.disabled(model.busy).frame(minHeight: 44)
                }
                Button(model.t("Connect Google", "اتصال به Google")) { Task { await model.connectGoogle(calendar: readGoogleCalendar) } }.disabled(model.busy).frame(minHeight: 44)
                Button(model.t("Sync and review now", "همگام‌سازی و بازبینی اکنون")) { model.refresh() }.disabled(model.busy).frame(minHeight: 44)
                if !model.google.configured { Text(model.t("Google connection requires the project owner to configure Apple OAuth in the Android app's Google Cloud project. The code is ready, but this build is not configured for live Google access.", "اتصال Google به ثبت OAuth مخصوص Apple توسط صاحب پروژه، در همان پروژهٔ Google Cloud نسخهٔ Android نیاز دارد. کد اتصال آماده است؛ این خروجی برای اتصال زنده به Google پیکربندی نشده است."))
                    .font(.caption).foregroundStyle(.secondary) }
                Text(model.t("Change logs sync supported records and preserve other Android domains without executing their tools. Keys, device calendar mirrors, notification permissions and quiet hours stay device-local. The creating device owns reminder delivery.", "گزارش تغییرات، داده‌های پشتیبانی‌شده را همگام و سایر دامنه‌های Android را بدون اجرای ابزارهایشان حفظ می‌کند. کلیدها، بازتاب تقویم دستگاه، مجوز اعلان و ساعات سکوت محلی می‌مانند. دستگاه سازنده مسئول اجرای یادآور است."))
                    .font(.caption).foregroundStyle(.secondary)
            }
            if !model.serviceStatus.isEmpty { Text(model.serviceStatus).font(.caption).textSelection(.enabled) }
        }.onAppear {
            followUp = model.snapshot.services?.followUp ?? FollowUpSettings()
            let c = Calendar.current, start = c.startOfDay(for: Date())
            quietFrom = c.date(byAdding: .minute, value: followUp.quietStart, to: start) ?? start
            quietTo = c.date(byAdding: .minute, value: followUp.quietEnd, to: start) ?? start
            readGoogleCalendar = model.snapshot.services?.googleCalendarEnabled == true
        }
    }
}
