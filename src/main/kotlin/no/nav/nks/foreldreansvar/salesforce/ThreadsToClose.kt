package no.nav.nks.foreldreansvar.salesforce

/**
 * An open `Thread__c` that a parent has on a child, as Salesforce returned it.
 * The idents are null when the lookup field is empty in Salesforce.
 */
data class OpenThread(
    /** The Salesforce record Id of the thread. */
    val id: String,
    /** From `CRM_Account__r.INT_PersonIdent__c`: the child the thread is about. */
    val barnIdent: String?,
    /** From `CRM_Contact__r.Account.INT_PersonIdent__c`: the parent who wrote it. */
    val forelderIdent: String?,
) {
    override fun toString(): String = "OpenThread(id=$id, barnIdent=<masked>, forelderIdent=<masked>)"
}

/** A thread we have decided to close, together with the child state that made us decide so (for logging). */
data class ThreadToClose(
    val thread: OpenThread,
    val oppdatering: ForeldreansvarOppdatering,
)

data class CloseDecision(
    val toClose: List<ThreadToClose>,
    /** Open threads with no parent ident. We cannot tell whose they are, so we leave them open and only count them. */
    val withoutParent: List<OpenThread>,
)

/**
 * Decides which open threads to close. Pure function: no Salesforce or PDL calls, so it is easy to test.
 *
 * A thread is closed when its parent is not among the child's current [ForeldreansvarOppdatering.ansvarlige].
 * An empty `ansvarlige` therefore closes every parent thread on the child. Threads without a parent ident are left
 * open. Threads for children that are not in [oppdateringer] are ignored (the query should never return them).
 */
fun threadsToClose(
    oppdateringer: List<ForeldreansvarOppdatering>,
    openThreads: List<OpenThread>,
): CloseDecision {
    val perBarn = oppdateringer.associateBy { it.barnIdent }
    val toClose = mutableListOf<ThreadToClose>()
    val withoutParent = mutableListOf<OpenThread>()
    for (thread in openThreads) {
        val oppdatering = perBarn[thread.barnIdent] ?: continue
        val forelderIdent = thread.forelderIdent
        when {
            forelderIdent.isNullOrBlank() -> withoutParent += thread
            oppdatering.ansvarlige.none { it.ident == forelderIdent } -> toClose += ThreadToClose(thread, oppdatering)
        }
    }
    return CloseDecision(toClose, withoutParent)
}
