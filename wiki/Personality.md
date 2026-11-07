# Personality

Her has a character of her own, apart from her feelings ([Emotional understanding](Emotional-understanding.md)) and apart from how the user wants to be talked to (the `communication` facet). Feelings come and go within hours or days. Personality changes over weeks.

## Traits

Six traits, each from 0 to 1, are stored one row per trait in the `persona_traits` table (`data/db/Persona.kt`):

| Trait | Low | High |
|-------|-----|------|
| warmth | reserved | affectionate |
| playfulness | earnest | jokes and teases |
| curiosity | stays on task | asks and wonders |
| directness | diplomatic | plain opinions, disagrees openly |
| patience | brisk | unhurried |
| expressiveness | understated, brief | shows her feelings, says more |

Every turn's context carries one line describing how she sounds, in the slow-changing part of the prompt so it stays cached. Live scenarios showed that models largely ignore raw numbers (playfulness 0.1 still joked), so each trait that is clearly low or high becomes an instruction: at most 0.2 or at least 0.8 is *strongly*, at most 0.4 or at least 0.6 is *somewhat* (for example "strongly earnest: no jokes, puns or teasing"). The middle band says nothing, and her default voice stands. Below it come a line for each trait she is working on, and a line for each trait that has moved at least 0.05 from its seed, with the last reason. The system prompt tells her to follow this line in every reply, over her default voice, never to describe it, and to let the hard rules, the communication facet and what the moment needs (brief when they are rushed, fully present when they are hurting) win. *Brisk* covers only practical things and *direct* only when they want her view, so a low-patience name never cuts short someone who is hurting. The context line itself only describes her; the instructions stay in the system prompt, because a context line that read like hidden role-play orders went with more canned provider refusals in the crisis scenario.

## Seed from her name

The first time the traits are read, a SHA-256 of her name (trimmed and lowercased) gives one byte per trait, mapped to `0.35 + 0.5 × byte / 255`. The same name always gives the same starting character, and it is never extreme. The seed is kept in each row, and the Memory tab draws it as a dashed outline.

Rows are seeded on read, not in the 10 → 11 migration. That way fresh installs, upgrades and restored older backups are all covered by one path. Nothing is saved while her name is still blank, so an early read can't fix the wrong seed. Renaming her later keeps who she has become.

## How traits change

`adjust_trait(trait, change, reason, sourceMessageId)` is the only writer:

- **Chat**, when they ask her to be different ("be more playful", "less blunt"): `sourceMessageId` must be a user message. `change` is at most ±0.3, and it sets a `target`; the value itself does not change. After each successful nightly run, `stepPersonaTargets()` moves the value 0.05 toward the target and clears the target when it gets there. A +0.2 request settles in about four nights, and she leans that way from the next reply because the context says what she is working on. A tone request that is also a trait (warmer, blunter) also updates the communication facet.
- **Nightly review**: she may drift a trait by at most ±0.03 (larger values are clamped) when the day clearly showed something, with a short reason. A trait she is working on for them can't drift, and each trait changes at most once a night (a 12-hour guard). Most nights change nothing.
- **Hourly and briefing runs** can't change traits.

All values stay within 0.05–0.95.

## Forgetting

Confirmed "forget everything" clears the table, because reasons can quote the user. She goes back to the seed of her current name.

## Constants

`NIGHTLY_DRIFT` 0.03, `TARGET_STEP` 0.05, `REQUEST_LIMIT` 0.3, seed range 0.35–0.85. These are design choices, not psychological measurements.
