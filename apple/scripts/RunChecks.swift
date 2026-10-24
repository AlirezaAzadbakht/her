import Foundation

@main struct RunChecks {
    @MainActor static func main() async {
        do {
            let results = try await CoreChecks.run()
            for result in results { print("PASS: \(result)") }
            print("\(results.count) contract checks passed")
        } catch {
            print("FAIL: \(error)")
            exit(1)
        }
    }
}
