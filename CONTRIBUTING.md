# Contributing to Her

Thanks for wanting to help. This page covers why it's worth it, when to send something, and the few rules every change follows. The [wiki](wiki/Home.md) has the details.

## Why contribute

Her is an MIT-licensed personal assistant that people run with their own model, and their API key never leaves the phone. Everything that decides how she behaves is in this repo, so when she gets something wrong, you can see why and change it.

Merged work ships in the next signed APK on [GitHub Releases](https://github.com/AlirezaAzadbakht/her/releases) and [Cafe Bazaar](https://cafebazaar.ir/app/com.her), and gets a line in the [changelog](CHANGELOG.md).

What helps most:

- **Scenarios and bug reports.** A [scenario](wiki/Scenario-engine.md) (`scenarios/<id>.json`) that catches something she gets wrong is often worth more than a fix. Mark it `"pending": true` if she can't pass it yet.
- **Persian and other languages.** Persian phrasing, Jalali dates and Persian times, right-to-left layout, the [Persian website](docs/fa/index.html), or a new language.
- **Fixes and features.** The app, its tools, the background passes, sync.
- **Docs and website.** The [wiki](wiki/Home.md), the [README](README.md), the [website](docs/index.html).

## When

Send a pull request whenever you like. You don't need to open an issue first.

Open an issue instead when you've found a problem but don't have a fix, or when you want a yes or no before starting something big. If you want early feedback on the direction, open a draft pull request.

## Ground rules

Every change keeps these. They come from [Home](wiki/Home.md#hard-product-rules) and [Product](wiki/Product.md), and a change that breaks one won't be merged.

- Text only, and one conversation for the life of the app.
- She never claims to be human, and never invents a body, a room or the weather.
- API keys stay in Keystore-backed preferences. They are never written to Room and never synced.
- The person talks naturally and never needs tool vocabulary ("create a task", "add a grocery").
- The model only proposes tool calls. The app validates them and enforces budgets, permissions and timestamps.
- Destructive actions ask for confirmation first.

## Other platforms

Android is the product. Other clients (iOS, macOS, desktop) are welcome as experimental work if they:

- live in their own top-level folder (for example `apple/`)
- have their own workflow file under `.github/workflows/`
- leave the Android build and its CI unchanged
- are marked experimental wherever they're mentioned: README, website, changelog

## Setting up

[Local development](wiki/Local-development.md) covers the toolchain, the emulator and the tests. In short:

```bash
source scripts/env.sh
make apk       # build the debug APK
make emulate   # boot the emulator, install, launch
make test      # offline unit tests
```

## Making the change

- Branch from `main`. Keep one change per pull request.
- **Changing how she behaves** (the prompt in `Identity.kt`, tools, classification): add or update a scenario and run it live with `make scenario ID=<id>`. That needs your own model and key in a repo-root `.env`; see [Scenario engine](wiki/Scenario-engine.md). If you change a tool schema, re-record the affected `ci-core` cassettes with `make scenario-record ID=<id>` and commit them with the change. No key? Say so in the pull request and the maintainer will run them.
- **Changing the UI:** check light and dark themes, and right-to-left with Persian text. Add screenshots to the pull request.
- Never commit `.env`, `local.properties`, `.toolchain/`, `*.jks` or APKs.

## Changelog and docs

Anything a person using the app would notice gets one line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md), under Added, Changed or Fixed. Write it for that person, not in code terms. Internal refactors and test-only changes don't need one.

A major new capability also gets a row in the README's Features table, and a card in "What she can do" on [docs/index.html](docs/index.html) and [docs/fa/index.html](docs/fa/index.html). If it has its own wiki page, link that from the README Documentation table and [wiki/Home.md](wiki/Home.md).

## Commits

Match the history. The subject is one sentence that says what changes for the person, ending with a period:

```
Name the day when confirming a saved birthday or anniversary.
```

The body explains why, and what you checked.

## Before you open the pull request

- [ ] `make test` passes
- [ ] Scenarios for changed behaviour were run, or the pull request says why not
- [ ] A changelog line, if a person would notice the change
- [ ] Docs updated where they describe what you changed
- [ ] No keys, `.env`, keystores or personal data

CI ([android.yml](.github/workflows/android.yml)) runs the unit tests, lint, debug and release builds and the `ci-core` scenario replay. It has to be green before merging.

## Reporting bugs

Include:

- the app version and Android version
- the provider and model you use (never the key)
- what you said, what she did, and what you expected

Remove personal details first. Rows from the Memory SQL navigator are often the quickest way to show what she stored.

## Security

Don't report security problems in a public issue. Use **Report a vulnerability** on the repository's [Security tab](https://github.com/AlirezaAzadbakht/her/security).

## AI-assisted contributions

These are fine. You're responsible for the result, and you must have actually run whatever you say you ran.

## License

Your contributions are released under the repository's [MIT License](LICENSE). There's no CLA.

## Releases

The maintainer tags and publishes releases ([CI and release](wiki/CI-and-release.md)). Don't bump the version or push tags in a pull request.
