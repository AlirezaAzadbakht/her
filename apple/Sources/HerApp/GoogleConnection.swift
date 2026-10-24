import Foundation
import SwiftUI
import HerCore
#if canImport(GoogleSignIn)
import GoogleSignIn
#endif

@MainActor final class GoogleConnection {
    static let driveScope = "https://www.googleapis.com/auth/drive.appdata"
    static let calendarScope = "https://www.googleapis.com/auth/calendar.readonly"
    var configured: Bool {
        let value = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String ?? ""
        let schemes = (Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]])?.flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] } ?? []
        return value.hasSuffix(".apps.googleusercontent.com") && !value.contains("$(") && schemes.contains(value.split(separator: ".").reversed().joined(separator: "."))
    }
    var accountID: String? {
        #if canImport(GoogleSignIn)
        return GIDSignIn.sharedInstance.currentUser?.userID
        #else
        return nil
        #endif
    }
    func connect(calendar: Bool) async throws {
        guard configured else { throw HerError.invalidTool("The maintainer must configure this Apple build's Google OAuth client in the SAME Google Cloud project as Android. See apple/GOOGLE_SETUP.md. No Google account credentials should be entered here.") }
        #if canImport(GoogleSignIn)
        let id = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as! String
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: id)
        var scopes = [Self.driveScope]
        if calendar { scopes.append(Self.calendarScope) }
        #if os(macOS)
        guard let window = NSApp.keyWindow ?? NSApp.windows.first else { throw HerError.invalidTool("Open the app window before connecting Google.") }
        _ = try await GIDSignIn.sharedInstance.signIn(withPresenting: window, hint: nil, additionalScopes: scopes)
        #else
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }),
              let root = scene.windows.first(where: \.isKeyWindow)?.rootViewController else { throw HerError.invalidTool("Open the app before connecting Google.") }
        _ = try await GIDSignIn.sharedInstance.signIn(withPresenting: root, hint: nil, additionalScopes: scopes)
        #endif
        #else
        throw HerError.invalidTool("This direct-compiler build does not include Google Sign-In. Use the Xcode build with the GoogleSignIn package.")
        #endif
    }
    func token(scope: String) async throws -> String {
        #if canImport(GoogleSignIn)
        guard configured else { throw HerError.invalidTool("Google OAuth is not configured in this build.") }
        if GIDSignIn.sharedInstance.currentUser == nil { _ = try await GIDSignIn.sharedInstance.restorePreviousSignIn() }
        guard let user = GIDSignIn.sharedInstance.currentUser, user.grantedScopes?.contains(scope) == true else { throw HerError.invalidTool("Google permission is missing. Connect Google in Settings.") }
        let refreshed = try await user.refreshTokensIfNeeded()
        return refreshed.accessToken.tokenString
        #else
        throw HerError.invalidTool("Google Sign-In is unavailable in this build.")
        #endif
    }
    func disconnect() {
        #if canImport(GoogleSignIn)
        GIDSignIn.sharedInstance.signOut()
        #endif
    }
    func handle(_ url: URL) {
        #if canImport(GoogleSignIn)
        _ = GIDSignIn.sharedInstance.handle(url)
        #endif
    }
    func events(from: Date, to: Date) async throws -> [CalendarEntry] {
        let token = try await token(scope: Self.calendarScope)
        struct Page<T: Decodable>: Decodable { let items: [T]; let nextPageToken: String? }
        struct CalendarInfo: Decodable { let id: String; let selected: Bool?; let primary: Bool? }
        struct EventTime: Decodable { let dateTime: String?; let date: String?; let timeZone: String? }
        struct Event: Decodable { let id: String; let summary: String?; let start: EventTime?; let end: EventTime?; let location: String?; let status: String? }
        func get<T: Decodable>(_ url: URL, as: T.Type) async throws -> T {
            var r = URLRequest(url: url); r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization"); r.timeoutInterval = 30
            let (data, response) = try await URLSession(configuration: .ephemeral).data(for: r)
            guard let status = response as? HTTPURLResponse, (200..<300).contains(status.statusCode) else { throw HerError.invalidTool("Google Calendar read failed. Reconnect Google if needed.") }
            return try JSONDecoder().decode(T.self, from: data)
        }
        var calendars: [CalendarInfo] = [], page: String?
        repeat {
            var u = URLComponents(string: "https://www.googleapis.com/calendar/v3/users/me/calendarList")!
            if let page { u.queryItems = [.init(name: "pageToken", value: page)] }
            let p = try await get(u.url!, as: Page<CalendarInfo>.self); calendars += p.items; page = p.nextPageToken
        } while page != nil
        var result: [CalendarEntry] = []
        for calendar in calendars where calendar.selected == true || calendar.primary == true {
            page = nil
            repeat {
                var u = URLComponents(url: URL(string: "https://www.googleapis.com/calendar/v3/calendars")!.appendingPathComponent(calendar.id).appendingPathComponent("events"), resolvingAgainstBaseURL: false)!
                u.queryItems = [.init(name: "timeMin", value: Dates.string(from)), .init(name: "timeMax", value: Dates.string(to)), .init(name: "singleEvents", value: "true"), .init(name: "orderBy", value: "startTime"), .init(name: "maxResults", value: "2500")]
                if let page { u.queryItems?.append(.init(name: "pageToken", value: page)) }
                let p = try await get(u.url!, as: Page<Event>.self)
                func date(_ value: EventTime?) -> Date? {
                    if let s = value?.dateTime { return try? Dates.parse(s) }
                    if let day = value?.date {
                        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: value?.timeZone ?? TimeZone.current.identifier)
                        return f.date(from: day)
                    }
                    return nil
                }
                result += p.items.compactMap { e in
                    guard e.status != "cancelled", let start = date(e.start), let end = date(e.end) else { return nil }
                    return CalendarEntry(id: "google:\(calendar.id):\(e.id)", title: e.summary ?? "", start: start, end: end, location: e.location, writable: false)
                }
                page = p.nextPageToken
            } while page != nil
        }
        return result
    }
}
