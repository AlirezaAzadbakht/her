package com.her

import com.her.agent.runner.CallBudget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallBudgetTest {
    @Test
    fun stopsAtMaximum() {
        val budget = CallBudget(10)
        repeat(10) {
            assertTrue(budget.canCall())
            budget.consume()
        }
        assertFalse(budget.canCall())
        assertTrue(budget.used == 10)
    }

    @Test
    fun oneCallBudgetIsTheFinalCall() {
        val budget = CallBudget(1)
        assertTrue(budget.isFinalCall())
        budget.consume()
        assertFalse(budget.canCall())
        assertFalse(budget.isFinalCall())
    }
}
