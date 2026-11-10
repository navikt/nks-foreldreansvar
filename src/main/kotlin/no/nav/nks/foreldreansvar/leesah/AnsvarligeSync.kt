package no.nav.nks.foreldreansvar.leesah

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import no.nav.nks.foreldreansvar.TEAM_LOGS
import no.nav.nks.foreldreansvar.hentAnsvarlige
import no.nav.nks.foreldreansvar.pdl.PdlClient
import no.nav.nks.foreldreansvar.pdl.PdlException
import no.nav.nks.foreldreansvar.salesforce.ForeldreansvarOppdatering
import no.nav.nks.foreldreansvar.salesforce.OppdateringSender
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Keeps Salesforce in sync with PDL. Event = signal, PDL = truth: an event only tells us *which* child changed;
 * we then look up the child's full current state in PDL and send that.
 *
 * Per poll: one lookup per child (several events for the same child give one lookup), then batches of
 * [OppdateringSender.MAX_BATCH_SIZE]. If anything fails we throw, and the consumer retries the whole poll without
 * committing. That is safe because each element is the full state, so sending it again changes nothing.
 *
 * Counts each child as `foreldreansvar_sync_children_total{result, dry_run}` once its batches are accepted.
 * `result`: `with_ansvarlige`, `without_ansvarlige` (none, kode 6/7, or myndig) or `not_found` (not in PDL, sent as none).
 */
class AnsvarligeSync(
    private val pdlClient: PdlClient,
    private val sender: OppdateringSender,
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    private val clock: Clock = Clock.systemUTC(),
) : ForeldreansvarHendelseHandler {
    private val log = LoggerFactory.getLogger(AnsvarligeSync::class.java)

    // The consumer thread is not a coroutine, so we block it while we call PDL and Salesforce. That is what we want:
    // the next poll must wait until this one is delivered.
    override fun handle(hendelser: List<Personhendelse>) =
        runBlocking {
            val sisteHendelsePerBarn = sisteHendelsePerBarn(hendelser)
            val utenFnr = hendelser.count { it.fnr == null }
            if (utenFnr > 0) log.warn("Skipped {} Leesah events without fnr", utenFnr)

            val resultater =
                sisteHendelsePerBarn.map { (barnIdent, hendelse) ->
                    log.info(
                        TEAM_LOGS,
                        "Leesah: handling barnIdent={} from hendelseId={} ({} {}), {} events for the child in this poll",
                        barnIdent,
                        hendelse.hendelseId,
                        hendelse.opplysningstype,
                        hendelse.endringstype,
                        hendelser.count { it.fnr == barnIdent },
                    )
                    try {
                        val ansvarlige = hentAnsvarlige(pdlClient, barnIdent, clock)
                        val resultat = if (ansvarlige.ansvarlige.isEmpty()) "without_ansvarlige" else "with_ansvarlige"
                        resultat to
                            ForeldreansvarOppdatering(barnIdent, ansvarlige.ansvarlige, hendelse.hendelseId, ansvarlige.hentetTidspunkt)
                    } catch (_: PdlException.NotFound) {
                        // Fail closed: the ident no longer exists in PDL, so Salesforce should stop sharing for it.
                        "not_found" to ForeldreansvarOppdatering(barnIdent, emptyList(), hendelse.hendelseId, naa())
                    }
                }
            val oppdateringer = resultater.map { it.second }
            val ikkeFunnet = resultater.count { it.first == "not_found" }
            if (ikkeFunnet > 0) log.info("{} children not found in PDL, sending them with no ansvarlige", ikkeFunnet)

            val batcher = oppdateringer.chunked(OppdateringSender.MAX_BATCH_SIZE)
            batcher.forEach { batch ->
                sender.send(batch)
                batch.forEach {
                    log.info(
                        TEAM_LOGS,
                        "Salesforce {}: barnIdent={} ansvarlige={} hendelseId={}",
                        if (sender.dryRun) "dry run, not sent" else "sent",
                        it.barnIdent,
                        it.ansvarlige.map { a -> "${a.ident}:${a.ansvar}" },
                        it.hendelseId,
                    )
                }
            }
            resultater.groupingBy { it.first }.eachCount().forEach { (resultat, antall) ->
                meterRegistry
                    .counter("foreldreansvar.sync.children", "result", resultat, "dry_run", sender.dryRun.toString())
                    .increment(antall.toDouble())
            }
            log.info(
                "Handled {} Leesah events {}: {} children in {} batches",
                hendelser.size,
                hendelser.groupingBy { it.endringstype }.eachCount(),
                oppdateringer.size,
                batcher.size,
            )
        }

    private fun naa(): String = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS).toString()

    companion object {
        /** One event per child: the one PDL created last. Events without fnr are left out. */
        fun sisteHendelsePerBarn(hendelser: List<Personhendelse>): Map<String, Personhendelse> =
            hendelser
                .filter { it.fnr != null }
                .groupBy { it.fnr!! }
                .mapValues { (_, forBarn) -> forBarn.maxBy { it.opprettet } }
    }
}
