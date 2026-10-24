# Apple client preview

The experimental SwiftUI client lives in [`apple/`](../apple/README.md). It targets iOS 17+ and macOS 14+, shares a small Swift core, and is independent of the Android module.

Its current scope is one continuous conversation, device-local evidence-backed records, inspectable memory, pending-note retry, and Keychain credentials. It is not feature parity with Android: Google sync, calendar, reminders, background passes, learning and emotional/executive engines have not been ported.

See the [Apple README](../apple/README.md) for reproducible builds, physical-device signing requirements, data/privacy boundaries, checks and a contribution roadmap. The [Apple workflow](../.github/workflows/apple.yml) builds macOS and iOS Simulator apps without distribution signing.
