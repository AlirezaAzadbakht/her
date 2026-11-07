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
fun seedTraits(name: String?): Map<String, Double> {
    val key = name.orEmpty().trim().ifBlank { "her" }.lowercase(Locale.ROOT)
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

/** Context lines: the six values, then what she is working on for them and how she has grown since they met. */
fun personaLines(rows: List<PersonaTrait>): List<String> = buildList {
    add("Your personality now (0-1; let it color your voice, never say the numbers): " +
        rows.joinToString(", ") { "${it.trait} ${"%.2f".format(Locale.US, it.value)}" })
    rows.filter { it.target != null }.forEach {
        add("- working on being ${moreOrLess(it.value, it.target!!, it.trait)}, because they asked: ${it.reason.orEmpty()}")
    }
    rows.filter { it.target == null && abs(it.value - it.seed) >= 0.05 }.forEach {
        add("- since you met you have grown ${moreOrLess(it.seed, it.value, it.trait)}; last reason: ${it.reason.orEmpty()}")
    }
}
