# Maintainer setup: Apple access to Her's Google data

This requires **the Google Cloud project used by Android**. A contributor with only this repository cannot complete it. Do not request Google passwords, access tokens or client secrets from contributors/end users.

1. In that existing project enable Drive API and, for calendar reads, Calendar API. Use its existing consent configuration/test users.
2. Register the actual Apple bundle identifier. The official iOS/macOS Google Sign-In SDK uses an **iOS-type OAuth client**, including on macOS; follow Google's platform setup guide. Use separate clients if bundle identifiers differ.
3. Obtain the public client ID, e.g. 123-abc.apps.googleusercontent.com. Its callback scheme is com.googleusercontent.apps.123-abc.
4. Set Xcode build settings HER_GOOGLE_CLIENT_ID and HER_GOOGLE_REVERSED_CLIENT_ID. Info.plist consumes both. Set the registered PRODUCT_BUNDLE_IDENTIFIER and Signing Team. Public IDs can be supplied at build time; never commit tokens/signing keys.
5. Test with a properly signed build. Ad-hoc/unsigned compilation is not proof of Google/Keychain authentication.
6. Connect Google requests drive.appdata and optionally calendar.readonly, alongside basic SDK sign-in scopes. Official Google Sign-In handles credential storage, refresh and URL callbacks.
7. In a disposable account/data set, test Android→Apple task restore, Apple→Android completion, creator-owned reminder cancellation after sync, offline conflicting edits and sticky deletion. Check unrelated learning/companion JSON survives.
8. Only then advertise live interoperability as verified. The contributor had no access to this project or its credentials.

An unrelated Google project has a different appDataFolder namespace; it cannot restore Android data.

References: [platform setup](https://developers.google.com/identity/sign-in/ios/start-integrating), [sign-in integration](https://developers.google.com/identity/sign-in/ios/sign-in), [Drive appDataFolder](https://developers.google.com/workspace/drive/api/guides/appdata).

Required owner deliverables: public Apple OAuth client configuration and a signed test build. The repository contains native consent integration, transport, merge logic and mock tests; it does not confer authority over somebody else's Google project.
