# thinking-orbs

Her's dotted loading orbs are a Kotlin port of [thinking-orbs](https://github.com/Jakubantalik/Libraries.dev/tree/main/packages/thinking-orbs) by Jakub Antalik, MIT licensed (see `LICENSE`). Upstream demo: <https://orbs.jakubantalik.com>.

Source: `packages/thinking-orbs/ports/ios/ThinkingOrbsKit/Sources/ThinkingOrbsKit`, pinned to commit `422180dd7a5ac646c85deedc65500c4a74339127` (spec 1.0.0, thinking-orbs 0.3.1).

| Upstream (Swift) | Her (Kotlin, `app/src/main/java/com/her/ui/orbs/`) |
|---|---|
| `Core.swift` | `OrbCore.kt` |
| `OrbSpec.swift` | `OrbSpec.kt` |
| `Presets.swift` | `OrbPresets.kt` |
| `Orbits.swift` | `OrbOrbits.kt` |
| `Lattice.swift` | `OrbLattice.kt` |
| `Strands.swift` | `OrbStrands.kt` |
| `Morph.swift` | `OrbMorph.kt` |
| `Web.swift` | `OrbWeb.kt` |

The geometry is transcribed formula for formula. `spec/orbs-golden.json` is vendored at `app/src/test/resources/thinking-orbs/orbs-golden.json`, and `OrbGoldenTest` checks the Kotlin engine against it.

Her's changes are only in rendering: dots are tinted with Her's warm palette instead of the upstream grayscale ink.
