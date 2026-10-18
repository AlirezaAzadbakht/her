# Her

Text-only Android assistant. Start with [wiki/Home.md](wiki/Home.md) for the product rules, architecture, and workflows.

## Changelog and capabilities

Keep these in step with the code, in the same commit as the change:

- **Every user-visible feature, change, or fix** gets a line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md), under Added, Changed, or Fixed. Write it for the person using the app, not in code terms. Internal refactors and test-only changes don't need an entry.
- **A major feature** (a new capability someone would notice and talk about) is also listed as a capability:
  - a row in the `## Features` table in [README.md](README.md)
  - a card in the "What she can do" section of [docs/index.html](docs/index.html) and [docs/fa/index.html](docs/fa/index.html), the website
  - its wiki page, if it has one, linked from the README Documentation table and [wiki/Home.md](wiki/Home.md)
- **On release:** rename `[Unreleased]` to `[x.y.z] - YYYY-MM-DD`, start a new empty `[Unreleased]`, update the compare links at the bottom, commit, then push the tag (see [wiki/CI-and-release.md](wiki/CI-and-release.md)).
