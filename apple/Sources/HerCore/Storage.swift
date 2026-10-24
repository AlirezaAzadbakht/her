import Foundation
import Security

/// A single device-local store; this format is not Android Room/Drive sync compatible.
public struct LocalStore {
    public let url: URL
    public init(url: URL) { self.url = url }
    public static func applicationStore() throws -> LocalStore {
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                    appropriateFor: nil, create: true)
            .appendingPathComponent("HerApple", isDirectory: true)
        return LocalStore(url: directory.appendingPathComponent("state.json"))
    }
    public func load() throws -> Snapshot {
        guard FileManager.default.fileExists(atPath: url.path) else { return Snapshot() }
        return try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: url))
    }
    public func save(_ snapshot: Snapshot) throws {
        let directory = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let data = try JSONEncoder().encode(snapshot)
        try data.write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        #endif
        var excludedURL = url
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try excludedURL.setResourceValues(values)
    }
}

public enum CredentialStore {
    private static let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "org.her.apple", kSecAttrAccount as String: "llm-api-key"]
    public static func read() throws -> String {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return "" }
        guard status == errSecSuccess, let data = result as? Data,
              let value = String(data: data, encoding: .utf8) else {
            throw HerError.storage("Keychain could not be read (\(status)).")
        }
        return value
    }
    public static func write(_ key: String) throws {
        if key.isEmpty {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw HerError.storage("Keychain could not remove the key (\(status)).")
            }
            return
        }
        let attributes: [String: Any] = [kSecValueData as String: Data(key.utf8)]
        let update = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if update == errSecItemNotFound {
            var item = query
            item.merge(attributes) { _, value in value }
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            let status = SecItemAdd(item as CFDictionary, nil)
            guard status == errSecSuccess else { throw HerError.storage("Keychain could not save the key (\(status)).") }
        } else if update != errSecSuccess {
            throw HerError.storage("Keychain could not save the key (\(update)).")
        }
    }
}
