package com.her.data.remote

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class FetchedPage(val url: String, val title: String, val text: String)

internal const val MAX_PAGE_BYTES = 1_000_000L
private const val MAX_REDIRECTS = 5
private const val NOT_WEB = "I can only open http and https links."
private const val PRIVATE = "I can't open links to private or local addresses."

/**
 * GETs one public page and returns its readable text. Redirects are followed by hand so every hop
 * is vetted, and the connection is pinned to the addresses that were vetted (no DNS rebinding gap).
 * Refusals throw IllegalArgumentException; network trouble throws IOException.
 */
internal fun fetchPage(
    http: OkHttpClient,
    rawUrl: String,
    blocked: (InetAddress) -> Boolean = ::isPrivateAddress,
): FetchedPage {
    var url = rawUrl.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException(NOT_WEB)
    repeat(MAX_REDIRECTS + 1) {
        val addresses = try {
            InetAddress.getAllByName(url.host).toList()
        } catch (e: UnknownHostException) {
            throw IOException("No such site: ${url.host}")
        }
        if (addresses.isEmpty() || addresses.any(blocked)) throw IllegalArgumentException(PRIVATE)
        val client = http.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(Proxy.NO_PROXY)
            .dns(object : Dns {
                override fun lookup(hostname: String) = addresses
            })
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Her/1.0 (personal assistant; reads a page its user shared)")
            .header("Accept", "text/html,text/plain;q=0.9,*/*;q=0.5")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                val location = response.header("Location") ?: throw IOException("The page redirected nowhere.")
                url = url.resolve(location) ?: throw IllegalArgumentException(NOT_WEB)
                return@repeat
            }
            if (!response.isSuccessful) throw IOException("The page answered ${response.code}.")
            val type = response.body?.contentType()
            if (type != null && type.type != "text" && type.subtype !in setOf("json", "xml", "xhtml+xml")) {
                throw IllegalArgumentException("That link is not a text page (${type.type}/${type.subtype}).")
            }
            val body = response.peekBody(MAX_PAGE_BYTES).string()
            val html = type == null || "html" in type.subtype
            return FetchedPage(
                url = url.toString(),
                title = if (html) htmlTitle(body) else "",
                text = if (html) htmlToText(body) else body.trim(),
            )
        }
    }
    throw IOException("Too many redirects.")
}

/** Loopback, link-local, private ranges, carrier NAT, IPv6 unique-local, wildcard and multicast. */
internal fun isPrivateAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
        address.isAnyLocalAddress || address.isMulticastAddress
    ) {
        return true
    }
    val bytes = address.address
    return when (address) {
        is Inet4Address -> {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            first == 0 || first == 100 && second in 64..127
        }
        is Inet6Address -> (bytes[0].toInt() and 0xfe) == 0xfc
        else -> true
    }
}

internal fun htmlTitle(html: String): String =
    Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let(::htmlToText).orEmpty()

internal fun htmlToText(html: String): String = html
    .replace(Regex("(?is)<!--.*?-->"), " ")
    .replace(Regex("(?is)<(script|style|noscript|head|svg|template)\\b.*?</\\1\\s*>"), " ")
    .replace(Regex("(?i)</?(p|div|br|li|ul|ol|h[1-6]|tr|table|section|article|header|footer|blockquote|pre)\\b[^>]*>"), "\n")
    .replace(Regex("<[^>]*>"), "")
    .decodeEntities()
    .lineSequence()
    .map { it.replace(Regex("\\s+"), " ").trim() }
    .filter { it.isNotEmpty() }
    .joinToString("\n")

// ponytail: only the common named entities; add a full table if pages come back with raw &eacute; noise.
private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

private fun String.decodeEntities(): String = replace(Regex("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);")) { match ->
    val name = match.groupValues[1]
    val decoded = when {
        name.startsWith("#x", ignoreCase = true) -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
        name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
        else -> ENTITIES[name.lowercase()]
    }
    decoded ?: match.value
}

private fun codePoint(code: Int): String? = runCatching { String(Character.toChars(code)) }.getOrNull()
