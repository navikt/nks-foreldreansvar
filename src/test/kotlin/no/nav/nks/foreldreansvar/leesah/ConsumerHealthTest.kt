package no.nav.nks.foreldreansvar.leesah

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ConsumerHealthTest {
    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun instant() = now

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this
    }

    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val health = ConsumerHealth(clock, maxTimeBetweenPolls = Duration.ofMinutes(5))

    @Test
    fun `alive while polling regularly`() {
        clock.now = clock.now.plus(Duration.ofMinutes(4))
        health.polled()
        clock.now = clock.now.plus(Duration.ofMinutes(4))

        assertTrue(health.isAlive())
    }

    @Test
    fun `not alive when polling has stopped`() {
        clock.now = clock.now.plus(Duration.ofMinutes(6))

        assertFalse(health.isAlive())
    }

    @Test
    fun `not alive after failure`() {
        health.failed()

        assertFalse(health.isAlive())
    }
}
