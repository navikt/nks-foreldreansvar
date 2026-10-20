package no.nav.nks.foreldreansvar.leesah

import org.slf4j.LoggerFactory

/**
 * Receives Leesah events that passed [PersonhendelseFilter]. This is where 5.2 (notify Salesforce) plugs in.
 *
 * Contract:
 * - Throw to signal a temporary failure. The consumer retries, and if it keeps failing it stops without committing,
 *   so the event is read again after the pod restarts.
 * - Delivery is at-least-once, so the same event can arrive more than once. Use `hendelseId` to stay idempotent.
 * - Never put idents in exception messages or logs.
 */
fun interface ForeldreansvarHendelseHandler {
    fun handle(hendelse: Personhendelse)
}

/** Placeholder until 5.2: logs that a relevant event arrived, without idents. */
class LoggingForeldreansvarHendelseHandler : ForeldreansvarHendelseHandler {
    private val log = LoggerFactory.getLogger(LoggingForeldreansvarHendelseHandler::class.java)

    override fun handle(hendelse: Personhendelse) {
        log.info(
            "Relevant Leesah event: hendelseId={}, opplysningstype={}, endringstype={}, tidligereHendelseId={}",
            hendelse.hendelseId,
            hendelse.opplysningstype,
            hendelse.endringstype,
            hendelse.tidligereHendelseId,
        )
    }
}
