package no.nav.nks.foreldreansvar.salesforce

import no.nav.nks.foreldreansvar.AnsvarligDto
import org.slf4j.LoggerFactory

/**
 * The current state for one child, as we just looked it up in PDL. Every open thread a parent has on this child is
 * closed unless that parent is in [ansvarlige]. So an empty [ansvarlige] closes all the parents' threads on the child.
 * Handling the same element twice is harmless: the second time there are no open threads left to close.
 */
data class ForeldreansvarOppdatering(
    val barnIdent: String,
    val ansvarlige: List<AnsvarligDto>,
    /** The Leesah event that triggered the lookup (the latest one, if several arrived for the child). For tracing. */
    val hendelseId: String,
    /** When we looked the child up in PDL, ISO-8601 in UTC. */
    val hentetTidspunkt: String,
) {
    override fun toString(): String =
        "ForeldreansvarOppdatering(barnIdent=<masked>, ansvarlige=${ansvarlige.size}, hendelseId=$hendelseId, " +
            "hentetTidspunkt=$hentetTidspunkt)"
}

/**
 * Closes the open threads (henvendelser) that parents without foreldreansvar have on these children.
 * Gets at most [MAX_BATCH_SIZE] children at a time. Throws if anything failed, so the poll is retried.
 */
fun interface ThreadCloser {
    suspend fun closeThreads(oppdateringer: List<ForeldreansvarOppdatering>)

    /** True when no threads are really closed. Used as a metric tag, so dry-run numbers are not mistaken for real ones. */
    val dryRun: Boolean get() = false

    companion object {
        const val MAX_BATCH_SIZE = 50
    }
}

/** Used while `SALESFORCE_ENABLED=false`: does not call Salesforce at all, only logs counts. */
class DisabledThreadCloser : ThreadCloser {
    override val dryRun = true
    private val log = LoggerFactory.getLogger(DisabledThreadCloser::class.java)

    override suspend fun closeThreads(oppdateringer: List<ForeldreansvarOppdatering>) {
        log.info(
            "Salesforce disabled: would check threads for {} children, {} with no ansvarlige",
            oppdateringer.size,
            oppdateringer.count { it.ansvarlige.isEmpty() },
        )
    }
}
