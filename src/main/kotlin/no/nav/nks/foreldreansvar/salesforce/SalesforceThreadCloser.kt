package no.nav.nks.foreldreansvar.salesforce

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.nks.foreldreansvar.TEAM_LOGS
import org.slf4j.LoggerFactory

/**
 * Closes threads in Salesforce, one batch of children at a time:
 * 1. find the open threads on the children ([SalesforceClient.findOpenThreads]),
 * 2. decide which to close ([threadsToClose]),
 * 3. close them ([SalesforceClient.closeThreads]), but only when [lukkEnabled]. Otherwise we only log what we would close.
 *
 * Metrics, tagged `dry_run` (true when [lukkEnabled] is false), never with idents:
 * `foreldreansvar_threads_found_total`, `foreldreansvar_threads_closed_total` (would close, in a dry run) and
 * `foreldreansvar_threads_without_parent_total`.
 */
class SalesforceThreadCloser(
    private val client: SalesforceClient,
    private val lukkEnabled: Boolean,
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
) : ThreadCloser {
    override val dryRun = !lukkEnabled
    private val log = LoggerFactory.getLogger(SalesforceThreadCloser::class.java)

    override suspend fun closeThreads(oppdateringer: List<ForeldreansvarOppdatering>) {
        require(oppdateringer.size <= ThreadCloser.MAX_BATCH_SIZE) { "Batch too large: ${oppdateringer.size}" }
        if (oppdateringer.isEmpty()) return

        val openThreads = client.findOpenThreads(oppdateringer.map { it.barnIdent })
        val decision = threadsToClose(oppdateringer, openThreads)
        if (lukkEnabled && decision.toClose.isNotEmpty()) {
            client.closeThreads(decision.toClose.map { it.thread.id })
        }

        decision.toClose.forEach {
            log.info(
                TEAM_LOGS,
                "Salesforce thread {}: threadId={} barnIdent={} forelderIdent={} hendelseId={}",
                if (dryRun) "would be closed (dry run)" else "closed",
                it.thread.id,
                it.oppdatering.barnIdent,
                it.thread.forelderIdent,
                it.oppdatering.hendelseId,
            )
        }
        count("foreldreansvar.threads.found", openThreads.size)
        count("foreldreansvar.threads.closed", decision.toClose.size)
        count("foreldreansvar.threads.without_parent", decision.withoutParent.size)
        log.info(
            "Salesforce: checked {} children, found {} open threads, {} {}, {} left open without a parent ident",
            oppdateringer.size,
            openThreads.size,
            decision.toClose.size,
            if (dryRun) "would close (dry run, SALESFORCE_LUKK_ENABLED=false)" else "closed",
            decision.withoutParent.size,
        )
    }

    private fun count(
        name: String,
        amount: Int,
    ) = meterRegistry.counter(name, "dry_run", dryRun.toString()).increment(amount.toDouble())
}
