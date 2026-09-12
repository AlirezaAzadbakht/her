package com.her

import com.her.data.db.SqlGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlGuardTest {
    @Test
    fun acceptsReadOnly() {
        assertTrue(SqlGuard.isReadOnly("SELECT * FROM chat_messages"))
        assertTrue(SqlGuard.isReadOnly("  with x as (select 1) select * from x"))
        assertTrue(SqlGuard.isReadOnly("PRAGMA table_info(chat_messages)"))
        assertTrue(SqlGuard.isReadOnly("EXPLAIN QUERY PLAN SELECT 1"))
        assertTrue(SqlGuard.isReadOnly("SELECT 1;"))
        assertTrue(SqlGuard.isReadOnly("SELECT * FROM t WHERE name = 'a;b'"))
    }

    @Test
    fun rejectsWritesAndStacked() {
        assertFalse(SqlGuard.isReadOnly("DELETE FROM chat_messages"))
        assertFalse(SqlGuard.isReadOnly("DROP TABLE chat_messages"))
        assertFalse(SqlGuard.isReadOnly("INSERT INTO chat_messages VALUES (1)"))
        assertFalse(SqlGuard.isReadOnly("UPDATE chat_messages SET content = 'x'"))
        assertFalse(SqlGuard.isReadOnly("SELECT 1; DELETE FROM chat_messages"))
        assertFalse(SqlGuard.isReadOnly("VACUUM"))
        assertFalse(SqlGuard.isReadOnly(""))
    }
}
