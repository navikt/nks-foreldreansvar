package no.nav.nks.foreldreansvar.leesah

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Liveness of the Leesah consumer, read by `/internal/isalive`.
 *
 * Not alive if the consumer has stopped because of an error, or has not polled for [maxTimeBetweenPolls]
 * (it is stuck). Either way Nais restarts the pod, and we continue from the last committed offset.
 */
class ConsumerHealth(
    private val clock: Clock = Clock.systemUTC(),
    private val maxTimeBetweenPolls: Duration = Duration.ofMinutes(5),
) {
    @Volatile private var lastPoll: Instant = clock.instant()

    @Volatile private var failed = false

    fun polled() {
        lastPoll = clock.instant()
    }

    fun failed() {
        failed = true
    }

    fun isAlive(): Boolean = !failed && Duration.between(lastPoll, clock.instant()) <= maxTimeBetweenPolls
}
