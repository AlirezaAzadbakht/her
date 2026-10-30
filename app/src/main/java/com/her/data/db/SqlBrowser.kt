package com.her.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import java.util.Locale

data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val error: String? = null,
    val truncated: Boolean = false,
)

data class TableInfo(
    val name: String,
    val rowCount: Int,
)

/** Something she remembered, noted or learnt; [kind] is `remembered`, `noted` or `learnt`. */
data class RecentMemory(
    val kind: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)

object SqlGuard {
    private val ALLOWED = setOf("SELECT", "WITH", "PRAGMA", "EXPLAIN")

    fun isReadOnly(sql: String): Boolean {
        val trimmed = sql.trim().trimStart('\uFEFF')
        if (trimmed.isBlank()) return false
        val first = firstKeyword(trimmed) ?: return false
        if (first !in ALLOWED) return false
        val stripped = stripLiterals(trimmed)
        val afterSemi = stripped.substringAfter(';', missingDelimiterValue = "").trim()
        return afterSemi.isEmpty()
    }

    fun quoteIdent(name: String): String {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid identifier" }
        return "\"$name\""
    }

    private fun firstKeyword(sql: String): String? {
        val token = sql.takeWhile { !it.isWhitespace() && it != '(' && it != ';' }
        return token.uppercase(Locale.US).takeIf { it.isNotEmpty() }
    }

    private fun stripLiterals(sql: String): String {
        val out = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < sql.length) {
            val c = sql[i]
            when {
                quote != null -> {
                    if (c == quote) {
                        if (i + 1 < sql.length && sql[i + 1] == quote) {
                            i += 1
                        } else {
                            quote = null
                        }
                    }
                    out.append(' ')
                }
                c == '\'' || c == '"' || c == '`' -> {
                    quote = c
                    out.append(' ')
                }
                else -> out.append(c)
            }
            i += 1
        }
        return out.toString()
    }
}

class SqlBrowser(private val db: HerDatabase) {
    /** Reads up to [rowCap] rows after skipping [offset], so any read-only statement can be paged. */
    fun query(sql: String, rowCap: Int = 200, offset: Int = 0): QueryResult {
        if (!SqlGuard.isReadOnly(sql)) {
            return QueryResult(emptyList(), emptyList(), error = "Only read-only queries are allowed.")
        }
        val cap = rowCap.coerceIn(1, HARD_CAP)
        return try {
            db.query(SimpleSQLiteQuery(sql)).use { cursor ->
                val cols = (0 until cursor.columnCount).map { cursor.getColumnName(it) }
                val rows = ArrayList<List<String?>>(minOf(cap, 32))
                if (offset > 0) cursor.moveToPosition(offset - 1)
                var n = 0
                while (n < cap && cursor.moveToNext()) {
                    rows += (0 until cursor.columnCount).map { i ->
                        if (cursor.isNull(i)) null else runCatching { cursor.getString(i) }.getOrNull()
                    }
                    n += 1
                }
                QueryResult(cols, rows, truncated = cursor.moveToNext())
            }
        } catch (e: Exception) {
            QueryResult(emptyList(), emptyList(), error = e.message ?: "Query failed")
        }
    }

    fun tables(): List<TableInfo> {
        val listed = query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%' ORDER BY name",
            rowCap = HARD_CAP,
        )
        if (listed.error != null) return emptyList()
        return listed.rows.mapNotNull { row ->
            val name = row.firstOrNull() ?: return@mapNotNull null
            if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return@mapNotNull null
            val count = query("SELECT COUNT(*) FROM ${SqlGuard.quoteIdent(name)}", rowCap = 1)
                .rows.firstOrNull()?.firstOrNull()?.toIntOrNull() ?: 0
            TableInfo(name, count)
        }
    }

    fun recentMemories(limit: Int = 5): List<RecentMemory> = query(
        """
        SELECT kind, content, createdAt, updatedAt FROM (
            SELECT 'remembered' AS kind, content, createdAt, updatedAt FROM long_term_memories WHERE deletedAt IS NULL AND status = 'ACTIVE'
            UNION ALL SELECT 'noted', content, createdAt, updatedAt FROM short_term_memories WHERE deletedAt IS NULL
            UNION ALL SELECT 'learnt', content, createdAt, updatedAt FROM user_understandings WHERE deletedAt IS NULL AND status = 'ACTIVE'
        ) ORDER BY updatedAt DESC LIMIT $limit
        """,
        rowCap = limit,
    ).rows.map { RecentMemory(it[0].orEmpty(), it[1].orEmpty(), it[2]?.toLongOrNull() ?: 0, it[3]?.toLongOrNull() ?: 0) }

    companion object {
        const val HARD_CAP = 500
    }
}
