package com.her.data.retrieval

import com.her.core.lexicalOverlap
import com.her.core.recencyScore
import com.her.data.repository.HerRepository
import com.her.domain.MemoryHit
import com.her.domain.MemoryStatus

interface EmbeddingProvider {
    suspend fun embed(texts: List<String>): List<FloatArray>
}

class NoOpEmbeddingProvider : EmbeddingProvider {
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
    @Suppress("unused") private val embeddings: EmbeddingProvider = NoOpEmbeddingProvider(),
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
        val now = System.currentTimeMillis()
        val hits = mutableListOf<MemoryHit>()

        if (wantShort) {
            val fts = if (query.isBlank()) emptyList() else repo.searchShort(query, 40)
            val fallback = if (fts.isEmpty()) repo.activeShort() else fts
            fallback.forEach { mem ->
                hits += MemoryHit(
                    id = mem.id,
                    memoryType = "short_term",
                    content = mem.content,
                    importance = mem.importance,
                    confidence = mem.confidence,
                    updatedAt = mem.updatedAt,
                    score = score(query, mem.content, mem.importance, mem.confidence, mem.updatedAt, now, relatedIds, mem.id),
                    source = mem.source.name,
                    sourceMessageId = mem.sourceMessageId,
                    derivedFromJson = null,
                )
            }
        }
        if (wantLong) {
            val fts = if (query.isBlank()) emptyList() else repo.searchLong(query, 40)
            val fallback = if (fts.isEmpty()) repo.activeLong().filter { it.status == MemoryStatus.ACTIVE } else fts
            fallback.forEach { mem ->
                hits += MemoryHit(
                    id = mem.id,
                    memoryType = "long_term",
                    content = mem.content,
                    importance = mem.importance,
                    confidence = mem.confidence,
                    updatedAt = mem.updatedAt,
                    score = score(query, mem.content, mem.importance, mem.confidence, mem.updatedAt, now, relatedIds, mem.id),
                    source = mem.source.name,
                    sourceMessageId = mem.sourceMessageId,
                    derivedFromJson = mem.derivedFromJson,
                )
            }
        }
        return hits
            .distinctBy { it.id }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun score(
        query: String,
        content: String,
        importance: Double,
        confidence: Double,
        updatedAt: Long,
        now: Long,
        relatedIds: Set<String>,
        id: String,
    ): Double {
        val semantic = lexicalOverlap(query, content)
        val recency = recencyScore(updatedAt, now)
        val related = if (id in relatedIds) 1.0 else 0.0
        return 0.40 * semantic + 0.20 * importance + 0.15 * confidence + 0.15 * recency + 0.10 * related
    }
}
