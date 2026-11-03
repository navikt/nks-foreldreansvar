package no.nav.nks.foreldreansvar.leesah

/**
 * Receives the relevant Leesah events from one poll (those that passed [PersonhendelseFilter]), never an empty list.
 *
 * Contract:
 * - Throw to signal a temporary failure. The consumer retries the whole list, and if it keeps failing it stops without
 *   committing, so the events are read again after the pod restarts.
 * - Delivery is at-least-once, so the same event can arrive more than once. Handle it idempotently.
 * - Never put idents in exception messages or logs.
 */
fun interface ForeldreansvarHendelseHandler {
    fun handle(hendelser: List<Personhendelse>)
}
