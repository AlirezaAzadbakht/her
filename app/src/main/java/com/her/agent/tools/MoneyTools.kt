package com.her.agent.tools

import com.her.core.ToolValidationException
import com.her.core.jsonObjectOf
import com.her.core.newId
import com.her.core.optStringOrNull
import com.her.core.requiredString
import com.her.data.db.ExpenseEntity
import org.json.JSONArray
import org.json.JSONObject

private const val DAY_MS = 24 * HOUR_MS

internal fun ToolRegistry.registerMoneyTools() {
    register(
        "log_expense",
        "Record money they say they spent (\"spent 200 on lunch\", \"the taxi was 50\"). One call per amount. Not for prices, budgets, or money they plan to spend.",
        objSchema(
            "amount" to num("Positive number, as they said it"),
            "what" to str("What it was for, in their words"),
            "currency" to str("Only if they named one, e.g. toman, EUR"),
            "category" to str("One lowercase word, e.g. food, transport, home"),
            "when" to str("When it was spent if not now. $WHEN_HINT"),
            required = listOf("amount", "what"),
        ),
    ) { a ->
        val amount = a.getDouble("amount")
        if (amount <= 0) throw ToolValidationException("amount must be positive")
        val now = nowMillis()
        val item = ExpenseEntity(
            id = newId(),
            amount = amount,
            currency = a.optStringOrNull("currency")?.trim(),
            what = a.requiredString("what").trim().take(200),
            category = a.optStringOrNull("category")?.trim()?.lowercase(),
            spentAt = parseWhen(a.optStringOrNull("when")) ?: now,
            createdAt = now,
            updatedAt = now,
            deviceId = repo.deviceId,
            version = 1,
            deletedAt = null,
        )
        repo.saveExpense(item)
        jsonObjectOf("ok" to true, "id" to item.id, "amount" to item.amount, "what" to item.what)
    }
    register(
        "get_expenses",
        "What they logged as spent in a period, with totals. Call before answering how much they spent; never add amounts from memory. Defaults to the last 30 days.",
        objSchema(
            "from" to str("Start of the period. $WHEN_HINT"),
            "to" to str("End of the period, exclusive. $WHEN_HINT"),
            "category" to str(),
        ),
    ) { a ->
        val to = parseWhen(a.optStringOrNull("to")) ?: (nowMillis() + 1)
        val from = parseWhen(a.optStringOrNull("from")) ?: (to - 30 * DAY_MS)
        val category = a.optStringOrNull("category")?.trim()?.lowercase()
        val rows = repo.expensesBetween(from, to).filter { category == null || it.category == category }
        // Amounts in different currencies are never added together.
        fun totals(key: (ExpenseEntity) -> String) = JSONObject().apply {
            rows.groupBy(key).forEach { (name, items) -> put(name, items.sumOf { it.amount }) }
        }
        val zone = nowZoned().zone
        jsonObjectOf(
            "ok" to true,
            "count" to rows.size,
            "totalByCurrency" to totals { it.currency ?: "unspecified" },
            "totalByCategory" to totals { "${it.category ?: "other"} (${it.currency ?: "unspecified"})" },
            "expenses" to JSONArray(rows.take(40).map {
                jsonObjectOf(
                    "id" to it.id, "amount" to it.amount, "currency" to it.currency, "what" to it.what, "category" to it.category,
                    "when" to java.time.Instant.ofEpochMilli(it.spentAt).atZone(zone).toLocalDateTime().toString(),
                )
            }),
            "notShown" to (rows.size - 40).coerceAtLeast(0),
        )
    }
}
