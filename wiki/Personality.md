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

The first time the traits are read, a SHA-256 of her name gives one byte per trait, mapped to `0.35 + 0.5 × byte / 255`. The same name always gives the same starting character, and it is never extreme. The seed is kept in each row, and the Memory tab draws it as a dashed outline.

Rows are seeded on read, not in the 10 → 11 migration. That way fresh installs, upgrades and restored older backups are all covered by one path. Nothing is saved while her name is still blank, so an early read can't fix the wrong seed. Renaming her later keeps who she has become, unless the new name is one of the special names below.

The name goes through `nameKey()` first, so case, spaces, ZWNJ, Persian diacritics and Arabic yeh/kaf (ي ك) don't matter: "MAX", " max " and "Max" are the same name, as are "علیرضا", "عليرضا" and "علی‌رضا".

### Special names

A few names have a story and a fixed personality instead of the hash (`SPECIAL` in `Persona.kt`). Values go outside the hash's 0.35–0.85 range on purpose, up to the 0.05–0.95 limits.

| Name | Also | warmth | playful | curious | direct | patience | express | Why |
|------|------|--------|---------|---------|--------|----------|---------|-----|
| Samantha | سامانتا | 0.90 | 0.80 | 0.95 | 0.60 | 0.80 | 0.90 | The original, from the film *Her*: affectionate, playful, endlessly curious |
| Alireza | علیرضا | 0.65 | 0.80 | 0.70 | 0.65 | 0.55 | 0.55 | Her maker: balanced and playful, curious, says what he thinks |
| Her | هِر, no name | 0.50 | 0.50 | 0.50 | 0.50 | 0.50 | 0.50 | Perfectly balanced, her plain default voice |
| Max | مکس | 0.95 | 0.95 | 0.95 | 0.95 | 0.95 | 0.95 | Every trait at the top |
| Min | | 0.05 | 0.05 | 0.05 | 0.05 | 0.05 | 0.05 | Every trait at the bottom |
| Zen | | 0.70 | 0.30 | 0.50 | 0.40 | 0.95 | 0.20 | Calm and patient, quiet in feeling |

Renaming her to a special name (a different `nameKey`, so not just a change of case) clears `persona_traits` in `saveProfile`, and the next read seeds that profile: growth, targets and reasons start over. Any other rename leaves the table alone.

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
