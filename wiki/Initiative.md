# Initiative

Her may start a conversation when nothing is due: to follow up on something they told her, to ask a question she has been wondering about, or because they have been quiet for a day. Her personality sets how often. When her message goes unanswered she feels it, and that feeling decides when she tries again.

The code is `companion/Initiative.kt`. The hourly pass is the only place she starts a conversation. Settings → Quiet hours & reminders → **She writes first** turns it off (`initiativeEnabled`, on by default, kept in backups).

## How often: her traits

She may start `round(3 × mean(warmth, curiosity, expressiveness))` conversations a day, from 0 to 3, counted on the profile clock:

| Character | A day |
|---|---|
| Her (all 0.5), Alireza | 2 |
| Samantha, Max | 3 |
| Zen, names that seed low | 1 |
| Min | 0, so she never starts one |

A trait that changes over the nights changes this too. See [Personality](Personality.md).

## When she holds back

`initiativeBlock` returns the first reason that applies:

1. The switch is off.
2. Quiet hours.
3. Her allowance is 0, or she has used today's allowance.
4. They wrote in the last 2 hours.
5. She sent anything on her own in the last 3 hours, the morning briefing included.
6. Their last two notifications were swiped away (`swipedNudges ≥ 2`).
7. Check-ins are paused or disabled. Asking her to pause check-ins pauses this too.
8. An opted-in check-in is due. That check-in is her reaching out already.
9. Her last opening was the check-in below and they haven't written since. She waits for them.
10. Her last opening is unanswered and she still feels wistful (`WISTFUL` score ≥ 0.1).

## What she starts about

`openings` lists what she could raise. Each opening has a key, and she passes that key to `start_conversation`:

| Key | From |
|---|---|
| `concern:<experienceId>` | Up to two held worries, from `heldConcerns` (the "Still on your mind" experiences), once the experience has rested 12 hours |
| `thread:<id>` | Up to two ACTIVE life threads last updated between 12 hours and 14 days ago. Threads that are sensitive, stale, dismissed, snoozed or on a check-in schedule are left out |
| `wonder:<id>` | One OPEN wondering, when none was asked in the last 3 days |
| `silence` | Their last message is 24 hours old or more |
| `checkin` | Two of her openings in a row met silence (not a swipe). When this applies it is the only opening |

An opening she used in the last 3 days is not offered again, except `checkin`. The 12-hour rest keeps her from raising a topic they just talked about, which live runs showed she would otherwise do a few hours after asking about it in chat.

## How the hourly pass uses them

`runHourly` adds one `reach:<key>:<date>` trigger per opening. A quiet hour then still wakes the model, but at most once per opening per day, and the background token cap still applies. The openings are appended to `HOURLY_PROMPT` as "You may start a conversation". The prompt tells her the app has already checked the timing and her character, so she reaches out unless it would feel wrong right now. Live runs showed that with a plain "you may" she stayed silent about half the time, even when the gate had approved the opening.

`start_conversation(about, content)` is offered only to hourly and catch-up runs:
- It rechecks the gate and refuses an opening that is not offered now.
- For a `wonder:` opening it marks the question ASKED, through the same helper `ask_wondering` uses.
- The runner delivers the text like a study nudge and saves it with `{"proactive":true,"initiative":"<key>"}`. That metadata is how openings are counted, so there are no separate counters.

The prompt asks for one or two short lines, grounded in what they said, with one easy question and nothing that needs an answer. `silence` picks up something they told her, like a friend texting on an ordinary day; it never says how long it has been or asks if they're okay. On the emulator, a plain "something light" read as "It's been a couple of days… hope you're doing okay", which is the check-in's line. `checkin` says she hasn't heard from them and hopes they're okay. It never guesses how they feel and asks nothing.

## Being ignored

These are app-written `EMOTION_EVENT` rows with `origin: APP`, a `feeling` and an `intensity`, so the existing projection decays them like any other event. The nightly review leaves them alone, because they are not FAST events.

| Event | When | Feeling |
|---|---|---|
| `UNANSWERED:<messageId>` | A swipe on her opening, or no reply for 3 hours (noticed at the next hourly pass, even one that skips) | WISTFUL, `(0.3 + 0.5 × (1 − patience))`, halved for no reply |
| `SILENCE:<messageId>` | A second no-reply opening in a row since their last message | CONCERNED, 0.3 |
| `ANSWERED:<userMessageId>` | They write within 24 hours of an opening | GLAD, 0.4 |

The ids are deterministic. A swipe that comes after a no-reply event upgrades that event instead of adding a second one. A swipe never leads to `SILENCE`: it shows they are there and said "not now".

How long she waits after one unanswered opening, from the 0.1 threshold:

| patience | after a swipe | after no reply |
|---|---|---|
| 0.05 (brisk) | ~16 h | ~8.5 h |
| 0.5 | ~11.5 h | ~6 h |
| 0.95 (patient) | ~7 h | ~3 h |

The feeling also shows in her orb and in "You feel right now". Chat instructions say a feeling from her own unanswered message is hers: she never mentions their silence or implies they owe her a reply.

## Scenarios

Scenarios keep initiative off unless `settings.initiative` is true, so older scenarios still test their own feature. Run these four with `gradle :app:scenarioTest -Pscenario.filter=initiative`:
- `initiative-follows-up-a-life-thread`: a life thread and a quiet afternoon lead to one message about it.
- `initiative-gives-space-when-unanswered`: an unanswered opening writes WISTFUL, and the next pass stays silent.
- `initiative-checks-in-once-after-two-silences`: two unanswered openings lead to one gentle check-in, then silence.
- `initiative-silence-stays-light`: after a quiet day she picks up something they told her, without an "are you okay".

`InitiativeTest` and three `AgentOrchestratorTest` cases cover the gate, the events and the delivery offline.
