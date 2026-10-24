import Foundation
import SwiftUI
import HerCore
#if os(iOS)
import BackgroundTasks
import UIKit
#else
import AppKit
#endif

@MainActor final class BackgroundCoordinator {
    static let refreshID = "org.her.apple.preview.refresh"
    static let processingID = "org.her.apple.preview.processing"
    #if os(macOS)
    private var activity: NSBackgroundActivityScheduler?
    var launchAgentInstalled: Bool { FileManager.default.fileExists(atPath: agentURL.path) }
    private var agentURL: URL { FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/LaunchAgents/org.her.apple.preview.refresh.plist") }
    #endif

    func register() {
        #if os(iOS)
        for id in [Self.refreshID, Self.processingID] {
            BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
                let work = Task { @MainActor in
                    do {
                        try await AppModel.shared.wakeup()
                        task.setTaskCompleted(success: !Task.isCancelled)
                    } catch { task.setTaskCompleted(success: false) }
                    AppModel.shared.background.schedule(enabled: AppModel.shared.shouldWake)
                }
                task.expirationHandler = { work.cancel() }
            }
        }
        #endif
    }
    func schedule(enabled: Bool) {
        #if os(iOS)
        if !enabled {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.refreshID)
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.processingID)
            return
        }
        // earlistBeginDate is a lower bound, never a guarantee of an hourly launch.
        let refresh = BGAppRefreshTaskRequest(identifier: Self.refreshID)
        refresh.earliestBeginDate = Date().addingTimeInterval(20 * 60)
        let processing = BGProcessingTaskRequest(identifier: Self.processingID)
        processing.requiresNetworkConnectivity = true; processing.requiresExternalPower = false
        processing.earliestBeginDate = Date().addingTimeInterval(3600)
        try? BGTaskScheduler.shared.submit(refresh)
        try? BGTaskScheduler.shared.submit(processing)
        #else
        guard !ProcessInfo.processInfo.arguments.contains("--background-once") else { return }
        if !enabled { activity?.invalidate(); activity = nil; return }
        guard activity == nil else { return }
        let scheduler = NSBackgroundActivityScheduler(identifier: Self.refreshID)
        scheduler.interval = 15 * 60; scheduler.tolerance = 5 * 60; scheduler.repeats = true
        scheduler.schedule { completion in
            Task { @MainActor in
                do { try await AppModel.shared.wakeup(); completion(.finished) } catch { completion(.deferred) }
            }
        }
        activity = scheduler
        #endif
    }
    #if os(macOS)
    func setLaunchAgent(_ enabled: Bool) throws {
        let domain = "gui/\(getuid())"
        func run(_ arguments: [String]) throws {
            let p = Process(); p.executableURL = URL(fileURLWithPath: "/bin/launchctl"); p.arguments = arguments
            p.standardOutput = FileHandle.nullDevice; p.standardError = FileHandle.nullDevice
            try p.run(); p.waitUntilExit()
            guard p.terminationStatus == 0 else { throw HerError.invalidTool("macOS could not change the login background job. Check Login Items & Extensions in System Settings.") }
        }
        if enabled {
            guard let executable = Bundle.main.executablePath, Bundle.main.bundleURL.pathExtension == "app" else { throw HerError.invalidTool("Run a packaged Her.app before enabling closed-app background work.") }
            let plist: [String: Any] = ["Label": Self.refreshID, "ProgramArguments": [executable, "--background-once"], "StartInterval": 900, "RunAtLoad": true, "ProcessType": "Background", "LimitLoadToSessionType": "Aqua"]
            try FileManager.default.createDirectory(at: agentURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0).write(to: agentURL, options: .atomic)
            do { try run(["bootstrap", domain, agentURL.path]) } catch { try? FileManager.default.removeItem(at: agentURL); throw error }
        } else if launchAgentInstalled {
            if (try? run(["print", domain + "/" + Self.refreshID])) != nil { try run(["bootout", domain + "/" + Self.refreshID]) }
            try FileManager.default.removeItem(at: agentURL)
        }
    }
    #endif
}
