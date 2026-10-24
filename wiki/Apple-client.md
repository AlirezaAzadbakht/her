# Apple client

The SwiftUI client in [apple](../apple/README.md) targets iOS 17+ and macOS 14+.

It includes conversation, source-backed records, native system and synced internal calendars, timed/repeating/conditional reminders, conversational person/place reminders, opt-in read-only automatic review and the Android-compatible Google Drive operation log. Google Calendar is read-only; external deletion requires native confirmation.

iOS controls background opportunities; exact hourly execution cannot be promised. Submitted clock notifications are independent of model/network. macOS can optionally install a user-controlled login job.

Live Google access needs the owner to register Apple clients in Android's Google Cloud project. That access was unavailable to the contributor. See [owner setup](../apple/GOOGLE_SETUP.md), [delivery limits](../apple/README.md#background-and-delivery-limits), [sync semantics](../apple/README.md#sync-semantics) and [build checks](../.github/workflows/apple.yml).

Other Android engines remain preserved context, not implemented Apple tools. Compilation/mocks, Simulator launch, hardware notification/calendar behavior and live Android interoperability are separate verification gates.
