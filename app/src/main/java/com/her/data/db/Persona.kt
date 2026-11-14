package com.her.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.flow.Flow

/** Her lasting character: seeded once from her name, grown in the nightly review, walked toward what they ask for. */
@Entity(tableName = "persona_traits")
data class PersonaTrait(
    @PrimaryKey val trait: String,
    val value: Double,
    val seed: Double,
    /** Where they asked her to go; each successful nightly run steps [value] toward it. */
    val target: Double? = null,
    val reason: String? = null,
    val updatedAt: Long,
)

@Dao
interface PersonaDao {
    @Query("SELECT * FROM persona_traits") suspend fun all(): List<PersonaTrait>
    @Query("SELECT * FROM persona_traits") fun observeAll(): Flow<List<PersonaTrait>>
    @Upsert suspend fun upsert(rows: List<PersonaTrait>)
    @Query("DELETE FROM persona_traits") suspend fun clear()
}

val TRAITS = listOf("warmth", "playfulness", "curiosity", "directness", "patience", "expressiveness")
private val ADJECTIVES = mapOf("warmth" to "warm", "playfulness" to "playful", "curiosity" to "curious",
    "directness" to "direct", "patience" to "patient", "expressiveness" to "expressive")

const val NIGHTLY_DRIFT = 0.03
const val TARGET_STEP = 0.05
const val REQUEST_LIMIT = 0.3

fun round2(v: Double): Double = Math.round(v * 100) / 100.0
fun clampTrait(v: Double): Double = round2(v.coerceIn(0.05, 0.95))

/** The same name always gives the same starting character, and never an extreme one. */
/** One rule for "the same name": case, spaces, Persian diacritics and Arabic yeh/kaf do not matter. */
fun nameKey(name: String?): String = name.orEmpty().lowercase(Locale.ROOT)
    .replace(Regex("[\\s\u200C\u064B-\u065F]"), "").replace('ي', 'ی').replace('ك', 'ک').ifBlank { "her" }

private val SAMANTHA = listOf(0.90, 0.80, 0.95, 0.60, 0.80, 0.90)
private val ALIREZA = listOf(0.65, 0.80, 0.70, 0.65, 0.55, 0.55)
private val HER = List(6) { 0.50 }
private val MAX = List(6) { 0.95 }

/** Names with a story, in [TRAITS] order. Documented in wiki/Personality.md, the README and the website. */
private val SPECIAL = mapOf(
    "samantha" to SAMANTHA, "سامانتا" to SAMANTHA, // the original, from the film Her
    "alireza" to ALIREZA, "علیرضا" to ALIREZA, // her maker: balanced and playful
    "her" to HER, "هر" to HER, // perfectly balanced, her plain default voice
    "max" to MAX, "مکس" to MAX,
    "min" to List(6) { 0.05 },
    "zen" to listOf(0.70, 0.30, 0.50, 0.40, 0.95, 0.20),
)

fun specialTraits(name: String?): Map<String, Double>? = SPECIAL[nameKey(name)]?.let { TRAITS.zip(it).toMap() }

fun seedTraits(name: String?): Map<String, Double> {
    specialTraits(name)?.let { return it }
    val key = nameKey(name)
    val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
    return TRAITS.withIndex().associate { (i, trait) -> trait to round2(0.35 + 0.5 * (bytes[i].toInt() and 0xFF) / 255.0) }
}

/** One nightly step toward [PersonaTrait.target]; the target clears on arrival. */
fun PersonaTrait.stepped(now: Long): PersonaTrait {
    val goal = target ?: return this
    val next = round2(if (abs(goal - value) <= TARGET_STEP + 1e-9) goal else value + TARGET_STEP * sign(goal - value))
    return copy(value = next, target = if (next == goal) null else goal, updatedAt = now)
}

fun moreOrLess(from: Double, to: Double, trait: String) = "${if (to > from) "more" else "less"} ${ADJECTIVES[trait] ?: trait}"

// Models follow a behavior far better than a number, so a clearly low or high trait becomes an instruction.
private val VOICE = mapOf(
    "warmth" to ("reserved: kind but matter-of-fact, not effusive" to "affectionate: openly caring and tender"),
    "playfulness" to ("earnest: no jokes, puns or teasing" to "playful: a light joke or gentle tease whenever the moment allows"),
    "curiosity" to ("focused: stay on what they asked, ask only when you need to" to "curious: when there is room, ask about them and wonder aloud"),
    "directness" to ("diplomatic: appreciate first, raise a concern softly as a question or suggestion" to
        "direct: when they want your view, give your honest opinion plainly and early"),
    "patience" to ("brisk: on practical things, get to the point fast" to "patient: unhurried, explain gently, never rush them"),
    "expressiveness" to ("understated: calm and brief, a few short sentences, no gushing" to
        "expressive: say in a few natural words how what they share makes you feel, and say a little more"),
)

/** How one trait should sound, or null in the middle band where her default voice stands. */
fun voiceOf(row: PersonaTrait): String? {
    val (low, high) = VOICE[row.trait] ?: return null
    return when {
        row.value <= 0.2 -> "strongly $low"
        row.value <= 0.4 -> "somewhat $low"
        row.value >= 0.8 -> "strongly $high"
        row.value >= 0.6 -> "somewhat $high"
        else -> null
    }
}

/** Context lines: how she sounds now, then what she is working on for them and how she has grown since they met. */
fun personaLines(rows: List<PersonaTrait>): List<String> = buildList {
    val voice = rows.mapNotNull(::voiceOf)
    add("Your personality now: " +
        if (voice.isEmpty()) "balanced, your default voice" else voice.joinToString("; "))
    rows.filter { it.target != null }.forEach {
        add("- working on being ${moreOrLess(it.value, it.target!!, it.trait)}, because they asked: ${it.reason.orEmpty()}")
    }
    rows.filter { it.target == null && abs(it.value - it.seed) >= 0.05 }.forEach {
        add("- since you met you have grown ${moreOrLess(it.seed, it.value, it.trait)}; last reason: ${it.reason.orEmpty()}")
    }
}
