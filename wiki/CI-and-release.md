# CI and release

Workflow: `.github/workflows/android.yml`.

## When it runs

| Trigger | What happens |
|---------|----------------|
| Push to `main` | `build` only |
| Pull request | `build` only |
| `workflow_dispatch` | `build` only |
| Tag `v*` | `build`, then `release` if build passed |

In-progress runs on the same ref are cancelled, **except** tag pushes.

## Build job

1. JDK 17 (Temurin), `gradle/actions/setup-gradle@v6`, Android SDK
2. `./gradlew testDebugUnitTest assembleDebug --no-daemon`
3. Upload artifact `her-debug`

CI uses the **wrapper**. That is fine on GitHub runners (`services.gradle.org` works there). Locally, prefer `gradle` after `source scripts/env.sh` — see [Local development](Local-development.md).

Unit tests must stay offline for Robolectric (prefetch task in `app/build.gradle.kts`). Simulation reports must not use a machine-specific path (`user.dir` + `build/reports`). Both of those failed the first `main` builds and are why **v0.1.1** exists.

`android-actions/setup-android@v4` defaults to installing `tools platform-tools`. `sdkmanager` no longer has `tools`, so CI passes `packages: ''` (licenses and cmdline-tools only). That is why **v0.1.6** exists.

## Version tags

```bash
git tag -a v0.1.2 -m "Her 0.1.2

Why this release exists."
git push origin v0.1.2
```

Tag shape: **`vMAJOR.MINOR.PATCH`**, optional suffix after a hyphen (`v1.2.3-beta`). CI parses the numeric triple:

- `VERSION_NAME` = tag without the leading `v`
- `VERSION_CODE` = `major * 10000 + minor * 100 + patch`

Examples: `v0.1.0` → code `100`, `v0.1.1` → code `101`.

Default `versionName` in `app/build.gradle.kts` is still `0.1.0` when those env vars are unset. Signed CI builds always take the tag.

If the tag commit is not on `origin/main`, push `main` first so the workflow file and the code agree. A tag-only push can still run Actions from the tagged tree, but the default branch will look stale.

Published so far:

| Tag | Notes |
|-----|--------|
| [v0.1.0](https://github.com/AlirezaAzadbakht/her/releases/tag/v0.1.0) | First product cut. Release APK job did not finish (CI tests failed). |
| [v0.1.1](https://github.com/AlirezaAzadbakht/her/releases/tag/v0.1.1) | CI fixes; signed `her-0.1.1.apk` attached |
| [v0.1.2](https://github.com/AlirezaAzadbakht/her/releases/tag/v0.1.2) | Single-utterance home, first-run names, RTL replies, Vazirmatn, setup ping fix |
| [v0.1.3](https://github.com/AlirezaAzadbakht/her/releases/tag/v0.1.3) | Smaller APKs (R8), device calendar writes, Jalali dates, scenario test pool |
| [v0.1.4](https://github.com/AlirezaAzadbakht/her/releases/tag/v0.1.4) | Persian and full-sentence memory search, save receipts with undo, notification Reply, nightly digest, full Drive sync, optional embeddings, scenarios out of CI |
| [v0.1.5](https://github.com/AlirezaAzadbakht/her/releases/tag/v0.1.5) | Real reminders and alarms, natural and Persian times, warm-glow UI with presence orbs, and a matching website. Release APK job did not finish (`sdkmanager` no longer has `tools`) |
| [v0.1.6](https://github.com/AlirezaAzadbakht/her/releases/tag/v0.1.6) | CI SDK setup skips the removed `tools` package so the signed 0.1.5 product cut can publish |

## Release job

Runs only for `refs/tags/v*`, after `build`.

1. Decode the keystore from secrets
2. `./gradlew assembleRelease` with `VERSION_NAME` / `VERSION_CODE` and signing env
3. Publish `her-{VERSION_NAME}.apk` on a GitHub Release titled `Her {VERSION_NAME}`
   - If the release already exists, upload `--clobber`
   - Otherwise `gh release create --generate-notes`

### Secrets

`Settings → Secrets and variables → Actions`:

| Secret | Value |
|--------|--------|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w 0 her-release.jks` |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Usually `her` |
| `RELEASE_KEY_PASSWORD` | Key password |

Keep **one** keystore for every update. The `.jks` is gitignored.

```bash
keytool -genkeypair -v \
  -keystore her-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias her
```

`GITHUB_TOKEN` is enough to create the release. It does **not** replace the signing secrets.

## Pushing from this machine

Port 22 to `github.com` is often closed here. Tag and branch pushes that worked used SSH on **443**:

```bash
GIT_SSH_COMMAND='ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new' \
  git push ssh://git@ssh.github.com:443/AlirezaAzadbakht/her.git HEAD:main
```

A GitHub OAuth token without the `workflow` scope will reject commits that touch `.github/workflows/`. Use SSH (or a token that includes `workflow`) for those.

## What “green” means

- `build` green: unit tests + debug APK artifact
- Tag + `release` green: signed APK on the GitHub Release
- `release` skipped on ordinary `main` pushes is expected
