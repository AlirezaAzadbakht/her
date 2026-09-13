package com.her.core

import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * A controllable clock. Each read advances by [tickMs] so rows written in the same turn keep a stable order.
 */
class FakeClock(
    startMillis: Long,
    private val zone: ZoneId = ZoneId.of("UTC"),
    private val tickMs: Long = 1,
) : TimeProvider {
    private val current = AtomicLong(startMillis)

    override fun nowMillis(): Long = current.getAndAdd(tickMs)

    override fun zoneId(): ZoneId = zone

    fun peek(): Long = current.get()

    fun advance(millis: Long) {
        current.addAndGet(millis)
    }

    fun set(millis: Long) {
        current.set(millis)
    }
}
