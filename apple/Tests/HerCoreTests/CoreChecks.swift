import Foundation
import HerCore

enum CheckError: Error { case failed(String) }

@MainActor enum CoreChecks {
    static func expect(_ condition: @autoclosure () throws -> Bool, _ message: String) throws {
        guard try condition() else { throw CheckError.failed(message) }
    }
    static func rejects(_ action: () throws -> Void) throws {
        do { try action() } catch { return }
        throw CheckError.failed("Expected rejection")
    }
    static func configuration() -> Configuration {
        var config = Configuration(); config.baseURL = "https://provider.example/v1"; config.model = "test-model"
        return config
    }
    static func call(kind: String = "memory", text: String = "Lives in Tehran", evidence: String = "I live in Tehran") -> ToolCall {
        let json = try! JSONSerialization.data(withJSONObject: ["kind": kind, "text": text, "evidence": evidence])
        return ToolCall(id: UUID().uuidString, function: .init(name: "save_record", arguments: String(decoding: json, as: UTF8.self)))
    }
    static func run() async throws -> [String] {
        var passed: [String] = []
        var config = configuration()
        try expect(try config.endpoint().absoluteString == "https://provider.example/v1/chat/completions", "Endpoint path")
        for url in ["http://provider.example/v1", "https://key@provider.example/v1", "https://provider.example/v1?api_key=secret", "file:///tmp/test", "https://provider.example/#fragment"] {
            config.baseURL = url; try rejects { _ = try config.endpoint() }
        }
        config.baseURL = "http://127.0.0.1:8000/v1"; _ = try config.endpoint()
        passed.append("HTTPS and local-only HTTP validation; reject URL credentials/query/fragment")

        let note = Note(text: "I live in Tehran and I should buy milk.")
        var state = Snapshot(); state.configuration = configuration(); state.notes = [note]
        _ = try RecordTools.execute(call(), note: note, snapshot: &state)
        _ = try RecordTools.execute(call(text: "Based in Tehran"), note: note, snapshot: &state)
        try expect(state.records.count == 1, "Retries must not duplicate records after paraphrasing")
        passed.append("Successful record writes deduplicate on retry")

        try rejects { _ = try RecordTools.execute(call(evidence: "I own a company"), note: note, snapshot: &state) }
        try rejects { _ = try RecordTools.execute(call(evidence: "  "), note: note, snapshot: &state) }
        try rejects { _ = try RecordTools.execute(call(kind: "alarm"), note: note, snapshot: &state) }
        try expect(state.records.count == 1, "Rejected writes must not modify state")
        passed.append("Reject missing verbatim evidence, empty evidence and unsupported kinds")
        let extra = ToolCall(id: "x", function: .init(name: "save_record", arguments: "{\"kind\":\"task\",\"text\":\"x\",\"evidence\":\"Tehran\",\"confirm\":\"yes\"}"))
        try rejects { _ = try RecordTools.execute(extra, note: note, snapshot: &state) }
        let destructive = ToolCall(id: "x", function: .init(name: "delete_everything", arguments: "{}"))
        try rejects { _ = try RecordTools.execute(destructive, note: note, snapshot: &state) }
        passed.append("Reject extra schema fields and destructive/unknown tools")
        let search = ToolCall(id: "x", function: .init(name: "search_records", arguments: "{\"query\":\"tehran\"}"))
        let result = try RecordTools.execute(search, note: note, snapshot: &state)
        try expect(result.contains("Tehran"), "Case-insensitive memory search")
        passed.append("Memory search returns inspectable evidence")

        let transportConfig = URLSessionConfiguration.ephemeral
        transportConfig.protocolClasses = [StubProtocol.self]
        let transport = OpenAIClient(session: URLSession(configuration: transportConfig))
        StubProtocol.status = 200
        StubProtocol.body = Data("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"}}]}".utf8)
        let reply = try await transport.complete(configuration: configuration(), key: "test-secret", messages: [.init(role: "user", content: "Hello")])
        try expect(reply.content == "Hello", "OpenAI-compatible response decoding")
        try expect(StubProtocol.lastRequest?.value(forHTTPHeaderField: "Authorization") == "Bearer test-secret", "Authorization header")
        try expect(StubProtocol.lastRequest?.url?.path == "/v1/chat/completions", "Transport path")
        StubProtocol.status = 401
        StubProtocol.body = Data("private provider error containing test-secret".utf8)
        do {
            _ = try await transport.complete(configuration: configuration(), key: "test-secret", messages: [])
            throw CheckError.failed("HTTP errors must fail")
        } catch HerError.http(401) {}
        StubProtocol.status = 200; StubProtocol.body = Data("{\"choices\":[]}".utf8)
        do {
            _ = try await transport.complete(configuration: configuration(), key: "", messages: [])
            throw CheckError.failed("Empty choices must fail")
        } catch HerError.invalidResponse {}
        passed.append("HTTP transport: endpoint, bearer auth, response decoding, safe HTTP errors and empty responses")

        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalStore(url: directory.appendingPathComponent("state.json"))
        try store.save(state)
        try expect(try store.load() == state, "State must round-trip")
        let raw = try String(contentsOf: store.url, encoding: .utf8)
        try expect(!raw.contains("apiKey"), "Credentials must not be in the snapshot")
        let attributes = try FileManager.default.attributesOfItem(atPath: store.url.path)
        try expect((attributes[.posixPermissions] as? NSNumber)?.intValue == 0o600, "Owner-only state permissions")
        passed.append("Atomic persistence, queue round-trip and owner-only file access")
        try Data("broken-json".utf8).write(to: store.url)
        try rejects { _ = try store.load() }
        try expect(try String(contentsOf: store.url, encoding: .utf8) == "broken-json", "Corrupt storage must not be overwritten")
        passed.append("Corrupt state fails visibly without overwriting user data")

        let recorder = ScriptedClient([
            .success(ChatMessage(role: "assistant", toolCalls: [call()])),
            .failure(HerError.http(503))
        ])
        var disk = Snapshot(); disk.configuration = configuration(); disk.notes = [note]
        do {
            _ = try await Agent.respond(note: note, snapshot: disk, key: "secret", client: recorder) { disk = $0 }
            throw CheckError.failed("Provider failure must propagate")
        } catch HerError.http(503) {}
        try expect(disk.records.count == 1 && disk.notes.first?.reply == nil, "Keep successful writes and pending note after failure")
        let retry = ScriptedClient([
            .success(ChatMessage(role: "assistant", toolCalls: [call(text: "Located in Tehran")])),
            .success(ChatMessage(role: "assistant", content: "Saved."))
        ])
        _ = try await Agent.respond(note: note, snapshot: disk, key: "secret", client: retry) { disk = $0 }
        try expect(disk.records.count == 1 && disk.notes.first?.reply == "Saved.", "Retry must complete without duplicate writes")
        passed.append("Agent failure/retry integration: durable writes, pending queue, idempotent recovery")

        let forever = ScriptedClient(Array(repeating: .success(ChatMessage(role: "assistant", toolCalls: [call()])), count: 9))
        do {
            _ = try await Agent.respond(note: note, snapshot: state, key: "", client: forever) { _ in }
            throw CheckError.failed("Infinite tool loop must stop")
        } catch HerError.toolBudget {}
        try expect(forever.requests == 8, "Agent must obey call budget")
        passed.append("Bounded agent loop stops after eight model calls")

        let client = ScriptedClient([.success(ChatMessage(role: "assistant", content: "ok"))])
        do {
            _ = try await Agent.respond(note: note, snapshot: state, key: "", client: client) { _ in throw HerError.storage("Disk full") }
            throw CheckError.failed("Storage error must propagate")
        } catch HerError.storage("Disk full") {}
        passed.append("Disk failures are not reported as successful writes")

        var forgotten = disk
        let unrelated = Note(text: "Queued unrelated note")
        forgotten.notes.append(unrelated)
        forgotten.forget(forgotten.records[0])
        try expect(forgotten.records.isEmpty && forgotten.latestReply.isEmpty && forgotten.notes == [unrelated], "Forgetting must remove conversation copies without requeuing delivered notes")
        passed.append("Explicit forgetting clears source records and recent context while preserving unrelated queue")
        passed += try await ServiceChecks.run()
        return passed
    }
}

final class ScriptedClient: CompletionClient {
    var responses: [Result<ChatMessage, Error>]
    var requests = 0
    init(_ responses: [Result<ChatMessage, Error>]) { self.responses = responses }
    func complete(configuration: Configuration, key: String, messages: [ChatMessage]) async throws -> ChatMessage {
        requests += 1
        guard !responses.isEmpty else { throw HerError.invalidResponse }
        return try responses.removeFirst().get()
    }
}

final class StubProtocol: URLProtocol {
    static var status = 200
    static var body = Data()
    static var lastRequest: URLRequest?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lastRequest = request
        let response = HTTPURLResponse(url: request.url!, statusCode: Self.status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

#if canImport(XCTest)
import XCTest
final class HerCoreTests: XCTestCase {
    @MainActor func testCoreContracts() async throws { _ = try await CoreChecks.run() }
}
#endif
