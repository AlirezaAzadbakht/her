package com.her

import com.her.core.COMMON_DONE
import com.her.core.COMMON_DROPPED
import com.her.core.parseEnum
import com.her.domain.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnumParseTest {
    @Test
    fun cancelledMapsToDropped() {
        val aliases = COMMON_DONE + COMMON_DROPPED
        assertEquals(TaskStatus.DROPPED, parseEnum<TaskStatus>("cancelled", aliases))
        assertEquals(TaskStatus.DROPPED, parseEnum<TaskStatus>("CANCELED", aliases))
        assertEquals(TaskStatus.DONE, parseEnum<TaskStatus>("completed", aliases))
        assertEquals(TaskStatus.OPEN, parseEnum<TaskStatus>("OPEN", aliases))
        assertNull(parseEnum<TaskStatus>("nope", aliases))
    }
}
