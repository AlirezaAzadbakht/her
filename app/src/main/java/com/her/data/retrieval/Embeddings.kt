package com.her.data.retrieval

import com.her.core.redactSecrets
import com.her.data.remote.LlmClient
import com.her.data.remote.LlmException
import com.her.data.secure.LlmSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Embeds text with the OpenAI-compatible `/embeddings` endpoint on the same base URL and key as chat. */
class OpenAiEmbeddingProvider(
    private val llmSettings: () -> LlmSettings,
    private val enabledModel: () -> String?,
    private val http: OkHttpClient = LlmClient.defaultClient(),
) : EmbeddingProvider {
    override fun model(): String? =
        enabledModel()?.trim()?.takeIf { it.isNotEmpty() && llmSettings().isConfigured }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        val model = model() ?: return texts.map { FloatArray(0) }
        val settings = llmSettings()
        return texts.chunked(BATCH).flatMap { batch -> request(settings, model, batch) }
    }

    private fun request(settings: LlmSettings, model: String, batch: List<String>): List<FloatArray> {
        val body = JSONObject().put("model", model).put("input", JSONArray(batch)).toString()
        val request = Request.Builder()
            .url(settings.baseUrl.trimEnd('/') + "/embeddings")
            .addHeader("Authorization", "Bearer ${settings.apiKey}")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw LlmException(redactSecrets("Embeddings request failed (${response.code}): ${raw.take(300)}"), response.code)
            }
            val vectors = parseEmbeddings(raw)
            if (vectors.size != batch.size) {
                throw LlmException("Embeddings response had ${vectors.size} vectors for ${batch.size} inputs")
            }
            return vectors
        }
    }

    private companion object {
        const val BATCH = 64
    }
}

/** Vectors from an `/embeddings` response, in input order. */
fun parseEmbeddings(raw: String): List<FloatArray> {
    val data = JSONObject(raw).optJSONArray("data") ?: JSONArray()
    return (0 until data.length())
        .map { data.getJSONObject(it) }
        .sortedBy { it.optInt("index") }
        .map { item ->
            val values = item.getJSONArray("embedding")
            FloatArray(values.length()) { values.getDouble(it).toFloat() }
        }
}

/** Null when the vectors cannot be compared (different sizes, empty, or zero length). */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Double? {
    if (a.isEmpty() || a.size != b.size) return null
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot += (a[i] * b[i]).toDouble()
        normA += (a[i] * a[i]).toDouble()
        normB += (b[i] * b[i]).toDouble()
    }
    if (normA == 0.0 || normB == 0.0) return null
    return dot / (sqrt(normA) * sqrt(normB))
}

fun FloatArray.toBlob(): ByteArray {
    val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buffer.putFloat(it) }
    return buffer.array()
}

fun ByteArray.blobToFloats(): FloatArray {
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / Float.SIZE_BYTES) { buffer.getFloat() }
}

fun contentHash(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
