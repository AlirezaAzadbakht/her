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

## Development rules

GitHub ruleset `basic-protection` on `main` (no bypass):

- **No direct pushes to `main`.** Work on a branch and open a PR.
- PRs are **squash-merge only**, with required status check `build` passing and all review threads resolved (0 approvals needed).
- No force-push to `main`, no deleting it.
- Tags (`v*`) are not covered; push them after the release commit has been merged to `main`.

Working here:

- Several sessions share this checkout: never switch branches in it. Use a worktree (`git worktree add ../her-<topic> -b <branch>`).
- Commit each verified step by explicit path (not `git add -A`), as you go.
- Push over SSH port 443 if port 22 is blocked (see [wiki/CI-and-release.md](wiki/CI-and-release.md)).
