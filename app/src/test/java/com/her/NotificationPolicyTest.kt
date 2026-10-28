package com.her

import com.her.core.QuietHours
import com.her.domain.ChatMessage
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.notify.NotifyDecision
import com.her.notify.alreadySaidProactively
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {
    @Test
    fun quietHoursSuppressNonUrgent() {
        val hours = QuietHours(23 * 60 + 30, 8 * 60)
        val night = ZonedDateTime.of(LocalDate.of(2026, 9, 12), LocalTime.of(1, 0), ZoneId.of("UTC"))
        assertTrue(hours.contains(night.toLocalTime()))
        val decision = NotifyDecision(notify = !hours.contains(night.toLocalTime()), reason = "quiet", key = "x")
        assertFalse(decision.notify)
    }

    @Test
    fun alreadySaidDetectsNearDuplicateProactiveText() {
        val now = 1_000_000L
        val previous = proactive("The passport renewal is overdue.", now - 2 * 60 * 60 * 1000)
        assertTrue(alreadySaidProactively(listOf(previous), "The passport renewal is overdue.", now))
        assertFalse(alreadySaidProactively(listOf(previous), "Milk is on the grocery list.", now))
        val stale = proactive("The passport renewal is overdue.", now - 13 * 60 * 60 * 1000)
        assertFalse(alreadySaidProactively(listOf(stale), "The passport renewal is overdue.", now))
        val chat = previous.copy(metadataJson = null)
        assertFalse(alreadySaidProactively(listOf(chat), "The passport renewal is overdue.", now))
    }

    private fun proactive(content: String, createdAt: Long) = ChatMessage(
        id = "m$createdAt",
        role = MessageRole.ASSISTANT,
        content = content,
        createdAt = createdAt,
        updatedAt = createdAt,
        deviceId = "d",
        version = 1,
        deletedAt = null,
        status = MessageStatus.SENT,
        metadataJson = """{"proactive":true}""",
    )
}
