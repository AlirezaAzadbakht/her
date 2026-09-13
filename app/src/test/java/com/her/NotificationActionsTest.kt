package com.her

import com.her.notify.NotificationAction
import com.her.notify.NotificationActions
import com.her.notify.Notifier
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationActionsTest {
    @Test
    fun replyCarriesTrimmedText() {
        assertEquals(
            NotificationAction.Reply("done, sent it"),
            NotificationActions.decide(Notifier.ACTION_REPLY, "  done, sent it ", null),
        )
    }

    @Test
    fun blankReplyIsIgnored() {
        assertEquals(NotificationAction.Ignore, NotificationActions.decide(Notifier.ACTION_REPLY, "   ", null))
        assertEquals(NotificationAction.Ignore, NotificationActions.decide(Notifier.ACTION_REPLY, null, null))
    }

    @Test
    fun laterRemembersWhatTheNotificationSaid() {
        assertEquals(
            NotificationAction.Later("Follow up later: Your passport renewal is due today."),
            NotificationActions.decide(Notifier.ACTION_LATER, null, "Your passport renewal is due today."),
        )
        assertEquals(
            NotificationAction.Later("Follow up later on the last notification."),
            NotificationActions.decide(Notifier.ACTION_LATER, null, null),
        )
    }

    @Test
    fun unknownActionIsIgnored() {
        assertEquals(NotificationAction.Ignore, NotificationActions.decide("com.her.NOTIFY_DONE", null, "x"))
    }
}
