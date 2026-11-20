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

/** How much she holds, counting only what is still active and not forgotten. */
data class MemoryCounts(
    val remembered: Int = 0,
    val noted: Int = 0,
    val learnt: Int = 0,
    val people: Int = 0,
    val dates: Int = 0,
)

/** What she knows under one long-term category ([kind] `remembered`) or understanding facet ([kind] `learnt`). */
data class Topic(
    val kind: String,
    val name: String,
    val items: List<String>,
)

data class PersonSummary(
    val name: String,
    val relationship: String?,
    val lastMentioned: Long?,
    val details: String,
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

    /**
     * One read-only SELECT/WITH that names no table outside [allowed]. The raw text is scanned, quotes
     * included, because SQLite accepts `"x"` and even `'x'` as a table name.
     */
    // ponytail: word scan, not a SQL parser; a blocked name in a column or string is refused too (the safe side).
    fun referencesOnly(sql: String, allowed: Set<String>, known: Set<String>): Boolean {
        if (!isReadOnly(sql)) return false
        if (firstKeyword(sql.trim().trimStart('\uFEFF')) !in setOf("SELECT", "WITH")) return false
        val blocked = known.map { it.lowercase(Locale.US) }.toSet() - allowed.map { it.lowercase(Locale.US) }.toSet()
        return Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(sql).none {
            val word = it.value.lowercase(Locale.US)
            word in blocked || word.startsWith("sqlite_") || word == "pragma"
        }
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

    /** For the agent: one SELECT over [RECORD_TABLES] only, so forgotten memories, chat and internals stay out. */
    fun queryRecords(sql: String, rowCap: Int): QueryResult {
        val known = query("SELECT name FROM sqlite_master", rowCap = HARD_CAP).rows.mapNotNull { it.firstOrNull() }.toSet()
        if (known.isEmpty() || !SqlGuard.referencesOnly(sql, RECORD_TABLES, known)) {
            return QueryResult(emptyList(), emptyList(), error = "Only one SELECT over these tables is allowed: ${RECORD_TABLES.sorted().joinToString()}")
        }
        return query(sql, rowCap)
    }

    /** Each record table with its column names, for the agent to write [queryRecords] against. */
    fun recordColumns(): Map<String, List<String>> = RECORD_TABLES.sorted().associateWith { table ->
        query("PRAGMA table_info(${SqlGuard.quoteIdent(table)})", rowCap = HARD_CAP).rows.mapNotNull { it.getOrNull(1) }
    }.filterValues { it.isNotEmpty() }

    /** Her own tables; SQLite's full-text shadow tables (`*_fts_segdir` and friends) stay reachable by query only. */
    fun tables(): List<TableInfo> {
        val listed = query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%' AND name NOT LIKE '%\\_fts\\_%' ESCAPE '\\' ORDER BY name",
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
            UNION ALL SELECT 'dated', title || ' · ' || dateIso, createdAt, updatedAt FROM important_dates WHERE deletedAt IS NULL
        ) ORDER BY updatedAt DESC LIMIT $limit
        """,
        rowCap = limit,
    ).rows.map { RecentMemory(it[0].orEmpty(), it[1].orEmpty(), it[2]?.toLongOrNull() ?: 0, it[3]?.toLongOrNull() ?: 0) }

    fun memoryCounts(): MemoryCounts {
        val row = query(
            """
            SELECT
                (SELECT COUNT(*) FROM long_term_memories WHERE deletedAt IS NULL AND status = 'ACTIVE'),
                (SELECT COUNT(*) FROM short_term_memories WHERE deletedAt IS NULL),
                (SELECT COUNT(*) FROM user_understandings WHERE deletedAt IS NULL AND status = 'ACTIVE'),
                (SELECT COUNT(*) FROM people WHERE deletedAt IS NULL),
                (SELECT COUNT(*) FROM important_dates WHERE deletedAt IS NULL)
            """,
            rowCap = 1,
        ).rows.firstOrNull() ?: return MemoryCounts()
        val n = row.map { it?.toIntOrNull() ?: 0 }
        return MemoryCounts(n[0], n[1], n[2], n[3], n[4])
    }

    /** Biggest topics first; within one, the most important items first. */
    fun knownTopics(): List<Topic> = query(
        """
        SELECT kind, name, content FROM (
            SELECT 'remembered' AS kind, category AS name, content, importance, updatedAt FROM long_term_memories WHERE deletedAt IS NULL AND status = 'ACTIVE'
            UNION ALL SELECT 'learnt', facet, content, importance, updatedAt FROM user_understandings WHERE deletedAt IS NULL AND status = 'ACTIVE'
        ) ORDER BY importance DESC, updatedAt DESC
        """,
        rowCap = HARD_CAP,
    ).rows
        .groupBy({ it[0].orEmpty() to it[1].orEmpty() }, { it[2].orEmpty() })
        .map { (key, items) -> Topic(key.first, key.second, items) }
        .sortedByDescending { it.items.size }

    fun people(): List<PersonSummary> = query(
        "SELECT name, relationship, lastMentioned, birthday, importantNotes, preferences FROM people WHERE deletedAt IS NULL ORDER BY lastMentioned IS NULL, lastMentioned DESC, name",
        rowCap = HARD_CAP,
    ).rows.map {
        PersonSummary(
            name = it[0].orEmpty(),
            relationship = it[1]?.takeIf(String::isNotBlank),
            lastMentioned = it[2]?.toLongOrNull(),
            details = listOfNotNull(it[0], it[1], it[3], it[4], it[5]).filter(String::isNotBlank).joinToString("\n\n"),
        )
    }

    companion object {
        const val HARD_CAP = 500

        /** The person's own records; never memories, chat, feelings or internals, which forgetting has to clear. */
        val RECORD_TABLES = setOf(
            "tasks", "goals", "commitments", "groceries", "reminders", "recurring_responsibilities", "routines",
            "important_dates", "calendar_events", "people", "projects", "open_loops", "learning_records", "expenses",
        )
    }
}
