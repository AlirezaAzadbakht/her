package com.her.data.retrieval

import com.her.core.lexicalOverlap
import com.her.core.recencyScore
import com.her.data.db.MemoryEmbeddingEntity
import com.her.data.repository.HerRepository
import com.her.domain.MemoryHit
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
    suspend fun search(
        query: String,
        memoryTypes: List<String> = emptyList(),
        relatedIds: Set<String> = emptySet(),
        limit: Int = 12,
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
    ): List<MemoryHit> {
        val types = memoryTypes.map { it.lowercase() }.toSet()
        val wantShort = types.isEmpty() || types.any { it.contains("short") }
        val wantLong = types.isEmpty() || types.any { it.contains("long") }
        val now = repo.clock.nowMillis()
        // With embeddings, a paraphrase can match a memory that shares no words, so rank the wider pool too.
        val model = embeddings.model()?.takeIf { query.isNotBlank() }
        val hits = mutableListOf<MemoryHit>()

        if (wantShort) {
            val fts = if (query.isBlank()) emptyList() else repo.searchShort(query, 40)
            val pool = when {
                model != null -> (fts + repo.activeShort().take(POOL_PER_TYPE)).distinctBy { it.id }
                fts.isEmpty() -> repo.activeShort()
                else -> fts
            }
            pool.forEach { mem ->
                hits += MemoryHit(
                    id = mem.id,
                    memoryType = "short_term",
                    content = mem.content,
                    importance = mem.importance,
                    confidence = mem.confidence,
                    updatedAt = mem.updatedAt,
                    score = 0.0,
                    source = mem.source.name,
                    sourceMessageId = mem.sourceMessageId,
                    derivedFromJson = null,
                )
            }
        }
        if (wantLong) {
            val fts = if (query.isBlank()) emptyList() else repo.searchLong(query, 40)
            val pool = when {
                model != null -> (fts + repo.activeLong().take(POOL_PER_TYPE)).distinctBy { it.id }
                fts.isEmpty() -> repo.activeLong()
                else -> fts
            }
            pool.forEach { mem ->
                hits += MemoryHit(
                    id = mem.id,
                    memoryType = "long_term",
                    content = mem.content,
                    importance = mem.importance,
                    confidence = mem.confidence,
                    updatedAt = mem.updatedAt,
                    score = 0.0,
                    source = mem.source.name,
                    sourceMessageId = mem.sourceMessageId,
                    derivedFromJson = mem.derivedFromJson,
                )
            }
        }
        val unique = hits.distinctBy { it.id }
        val similarity = if (model != null) semanticSimilarity(model, query, unique) else emptyMap()
        return unique
            .map { it.copy(score = score(query, it, now, relatedIds, similarity[it.id])) }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun score(query: String, hit: MemoryHit, now: Long, relatedIds: Set<String>, cosine: Double?): Double {
        val lexical = lexicalOverlap(query, hit.content)
        val semantic = cosine?.let { 0.35 * lexical + 0.65 * it.coerceIn(0.0, 1.0) } ?: lexical
        val recency = recencyScore(hit.updatedAt, now)
        val related = if (hit.id in relatedIds) 1.0 else 0.0
        return 0.40 * semantic + 0.20 * hit.importance + 0.15 * hit.confidence + 0.15 * recency + 0.10 * related
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
                dao.upsertAll(
                    fresh.map { (hit, vector) ->
                        MemoryEmbeddingEntity(hit.id, model, hashes.getValue(hit.id), vector.size, vector.toBlob(), now)
                    },
                )
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
    }
}
