package com.her

import com.her.agent.runner.TurnState
import com.her.ui.orbs.PresenceMood
import com.her.ui.orbs.presenceFor
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceStateTest {
    @Test
    fun thinkingUntilTheFirstToken() {
        assertEquals(PresenceMood.Thinking, presenceFor(TurnState.Thinking, revealing = false, draftNotEmpty = true, hasError = true))
        assertEquals(PresenceMood.Thinking, presenceFor(TurnState.Streaming(""), revealing = false, draftNotEmpty = false, hasError = false))
        assertEquals(PresenceMood.Thinking, presenceFor(TurnState.Streaming("  "), revealing = true, draftNotEmpty = false, hasError = false))
    }

    @Test
    fun speakingWhileTextArrivesOrIsStillRevealing() {
        assertEquals(PresenceMood.Speaking, presenceFor(TurnState.Streaming("Hi"), revealing = false, draftNotEmpty = true, hasError = false))
        assertEquals(PresenceMood.Speaking, presenceFor(TurnState.Idle, revealing = true, draftNotEmpty = true, hasError = true))
    }

    @Test
    fun idleMoodsFollowErrorThenDraft() {
        assertEquals(PresenceMood.Error, presenceFor(TurnState.Idle, revealing = false, draftNotEmpty = true, hasError = true))
        assertEquals(PresenceMood.Listening, presenceFor(TurnState.Idle, revealing = false, draftNotEmpty = true, hasError = false))
        assertEquals(PresenceMood.Idle, presenceFor(TurnState.Idle, revealing = false, draftNotEmpty = false, hasError = false))
    }
}
