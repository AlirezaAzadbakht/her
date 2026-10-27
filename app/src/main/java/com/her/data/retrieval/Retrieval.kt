package com.her.data.retrieval

import com.her.core.lexicalOverlap
import com.her.core.recencyScore
import com.her.data.db.MemoryEmbeddingEntity
import com.her.data.repository.HerRepository
import com.her.domain.LongTermMemory
import com.her.domain.MemoryHit
import com.her.domain.ShortTermMemory
import kotlin.coroutines.cancellation.CancellationException

interface EmbeddingProvider {
    /** The vector space in use; vectors from different models are never compared. Null when embeddings are off. */
    fun model(): String?

    suspend fun embed(texts: List<String>): List<FloatArray>
}

class NoOpEmbeddingProvider : EmbeddingProvider {
    override fun model(): String? = null
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { FloatArray(0) }
}

interface MemoryRanker {
    /**
     * [context] is the conversation just before [query]; it keeps the topic alive for short follow-ups.
     * [relatedIds] seed associations (e.g. people named in the message) besides the top hits.
     */
    suspend fun search(
        query: String,
        memoryTypes: List<String> = emptyList(),
        relatedIds: Set<String> = emptySet(),
        limit: Int = 12,
        context: String = "",
    ): List<MemoryHit>
}

class HybridRanker(
    private val repo: HerRepository,
    private val embeddings: EmbeddingProvider = NoOpEmbeddingProvider(),
) : MemoryRanker {
    override suspend fun search(
        query: String,
        memoryTypes: List<String>,
        relatedIds: Set<String>,
        limit: Int,
        context: String,
    ): List<MemoryHit> {
        val types = memoryTypes.map { it.lowercase() }.toSet()
        val wantShort = types.isEmpty() || types.any { it.contains("short") }
        val wantLong = types.isEmpty() || types.any { it.contains("long") }
        val now = repo.clock.nowMillis()
        // With embeddings, a paraphrase can match a memory that shares no words, so rank the wider pool too.
        val model = embeddings.model()?.takeIf { query.isNotBlank() }
        // ponytail: loads every live memory per search; fine for one person's phone, index links if it grows large.
        val allShort = if (wantShort) repo.activeShort().map { it.toHit() } else emptyList()
        val allLong = if (wantLong) repo.activeLong().map { it.toHit() } else emptyList()
        val hits = mutableListOf<MemoryHit>()

        if (wantShort) {
            val fts = (search(query) { repo.searchShort(it, 40) } + search(context) { repo.searchShort(it, 20) }).map { it.toHit() }
            hits += when {
                model != null -> fts + allShort.take(POOL_PER_TYPE)
                fts.isEmpty() -> allShort
                else -> fts
            }
        }
        if (wantLong) {
            val fts = (search(query) { repo.searchLong(it, 40) } + search(context) { repo.searchLong(it, 20) }).map { it.toHit() }
            hits += when {
                model != null -> fts + allLong.take(POOL_PER_TYPE)
                fts.isEmpty() -> allLong
                else -> fts
            }
        }
        val unique = hits.distinctBy { it.id }.filter { visible(it, now) }
        val similarity = if (model != null) semanticSimilarity(model, query, unique) else emptyMap()
        val first = unique.map { it.copy(score = score(query, context, it, now, false, similarity[it.id])) }
            .sortedByDescending { it.score }

        // One hop of association from what came to mind first, the way one memory brings up the next.
        val associated = associations(first.take(SEEDS), relatedIds, allShort + allLong)
        val pooled = first.map { it.id }.toSet()
        val extra = (allShort + allLong).filter { it.id in associated && it.id !in pooled && visible(it, now) }
        return (first + extra)
            .map { it.copy(score = score(query, context, it, now, it.id in associated, similarity[it.id])) }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private suspend fun <T> search(text: String, find: suspend (String) -> List<T>): List<T> =
        if (text.isBlank()) emptyList() else find(text)

    /** Memories learned in the same message, derived from one another, or explicitly related to a seed. */
    private suspend fun associations(seeds: List<MemoryHit>, relatedIds: Set<String>, all: List<MemoryHit>): Set<String> {
        val seedIds = seeds.map { it.id }.toSet() + relatedIds
        if (seedIds.isEmpty()) return emptySet()
        return buildSet {
            repo.relationships().forEach {
                if (it.sourceId in seedIds) add(it.targetId)
                if (it.targetId in seedIds) add(it.sourceId)
            }
            seeds.forEach { seed ->
                val derived = derivedIds(seed)
                all.filter { it.id != seed.id }.forEach { other ->
                    if ((seed.sourceMessageId != null && other.sourceMessageId == seed.sourceMessageId) ||
                        other.id in derived || seed.id in derivedIds(other)) add(other.id)
                }
            }
        }
    }

    private fun derivedIds(hit: MemoryHit): Set<String> {
        val array = runCatching { org.json.JSONArray(hit.derivedFromJson ?: return emptySet()) }.getOrNull() ?: return emptySet()
        return (0 until array.length()).map { array.optString(it) }.toSet()
    }

    /** Still live with the same content: a forget or correction since the pool was read keeps it out. */
    private suspend fun visible(hit: MemoryHit, now: Long): Boolean {
        val short = repo.getShort(hit.id)
        val long = repo.getLong(hit.id)
        return (short != null && short.deletedAt == null && short.content == hit.content && (short.expiresAt == null || short.expiresAt > now)) ||
            (long != null && long.deletedAt == null && long.status == com.her.domain.MemoryStatus.ACTIVE && long.content == hit.content)
    }

    private fun score(query: String, context: String, hit: MemoryHit, now: Long, related: Boolean, cosine: Double?): Double {
        // The current message leads; the turns before it only keep the topic in mind.
        val lexical = maxOf(lexicalOverlap(query, hit.content), CONTEXT_WEIGHT * lexicalOverlap(context, hit.content))
        val semantic = cosine?.let { 0.35 * lexical + 0.65 * it.coerceIn(0.0, 1.0) } ?: lexical
        // Being brought up again keeps a memory as fresh as rewriting it would.
        val recency = recencyScore(maxOf(hit.updatedAt, hit.lastConfirmedAt ?: 0L), now)
        return 0.40 * semantic + 0.20 * hit.importance + 0.15 * hit.confidence + 0.15 * recency + 0.10 * (if (related) 1.0 else 0.0)
    }

    /**
     * Cosine similarity between the query and each memory. Stored vectors are reused while the content and
     * model match; up to [MAX_NEW_PER_SEARCH] missing ones are embedded in the same request as the query.
     * Any failure falls back to lexical ranking.
     */
    private suspend fun semanticSimilarity(model: String, query: String, hits: List<MemoryHit>): Map<String, Double> {
        if (hits.isEmpty()) return emptyMap()
        return try {
            val dao = repo.db.embeddingDao()
            val hashes = hits.associate { it.id to contentHash(it.content) }
            val stored = dao.forIds(hits.map { it.id }, model)
                .filter { hashes[it.memoryId] == it.contentHash }
                .associate { it.memoryId to it.vector.blobToFloats() }
            val missing = hits.filter { it.id !in stored }.take(MAX_NEW_PER_SEARCH)
            val vectors = embeddings.embed(listOf(query) + missing.map { it.content })
            val queryVector = vectors.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
            val fresh = missing.zip(vectors.drop(1)).filter { (_, vector) -> vector.isNotEmpty() }
            if (fresh.isNotEmpty()) {
                val now = repo.clock.nowMillis()
                repo.learningTransaction {
                    // A concurrent forget/correction while the network request ran must not restore the cache.
                    val current = fresh.filter { (hit, _) -> visible(hit, now) }
                    dao.upsertAll(current.map { (hit, vector) ->
                        MemoryEmbeddingEntity(hit.id, model, hashes.getValue(hit.id), vector.size, vector.toBlob(), now)
                    })
                }
            }
            (stored + fresh.associate { (hit, vector) -> hit.id to vector })
                .mapNotNull { (id, vector) -> cosineSimilarity(queryVector, vector)?.let { id to it } }
                .toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.logDebug("embeddings", "Falling back to lexical ranking: ${e.message}")
            emptyMap()
        }
    }

    private companion object {
        const val POOL_PER_TYPE = 200
        const val MAX_NEW_PER_SEARCH = 64
        const val SEEDS = 3
        const val CONTEXT_WEIGHT = 0.6
    }
}

private fun ShortTermMemory.toHit() = MemoryHit(id, "short_term", content, importance, confidence, updatedAt, 0.0, source.name, sourceMessageId, null)

private fun LongTermMemory.toHit() = MemoryHit(id, "long_term", content, importance, confidence, updatedAt, 0.0, source.name, sourceMessageId, derivedFromJson, lastConfirmedAt)

private const val CONFIRM_EVERY_MS = 24L * 60 * 60 * 1000
private const val CUE_OVERLAP = 0.5

/**
 * The person bringing a memory up again strengthens it; Her merely reading it does not.
 * A retrieved long-term memory counts when most of its words appear in their message, at most once a day.
 */
suspend fun strengthenRecalled(repo: HerRepository, retrieved: List<MemoryHit>, userText: String) {
    val now = repo.clock.nowMillis()
    retrieved.filter {
        it.memoryType == "long_term" && now - (it.lastConfirmedAt ?: 0L) >= CONFIRM_EVERY_MS &&
            lexicalOverlap(it.content, userText) >= CUE_OVERLAP
    }.forEach { repo.confirmLong(it.id, now) }
}
