package no.nav.nks.foreldreansvar.salesforce

import no.nav.nks.foreldreansvar.AnsvarligDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThreadsToCloseTest {
    // Synthetic test idents, not real people.
    private val barn = "10101012345"
    private val barn2 = "20202012345"
    private val mor = "01020312345"
    private val far = "05060712345"

    private fun oppdatering(
        barnIdent: String = barn,
        vararg ansvarlige: String,
    ) = ForeldreansvarOppdatering(barnIdent, ansvarlige.map { AnsvarligDto(it, "felles") }, "hendelse-1", "2026-10-29T10:00:00Z")

    private fun CloseDecision.closedIds() = toClose.map { it.thread.id }

    @Test
    fun `closes only the thread of the parent who lost foreldreansvar`() {
        val decision =
            threadsToClose(
                listOf(oppdatering(barn, mor)),
                listOf(OpenThread("t-mor", barn, mor), OpenThread("t-far", barn, far)),
            )

        assertEquals(listOf("t-far"), decision.closedIds())
        assertEquals(oppdatering(barn, mor), decision.toClose.single().oppdatering)
    }

    @Test
    fun `no ansvarlige closes every parent thread on the child`() {
        val decision =
            threadsToClose(
                listOf(oppdatering(barn)),
                listOf(OpenThread("t-mor", barn, mor), OpenThread("t-far", barn, far)),
            )

        assertEquals(listOf("t-mor", "t-far"), decision.closedIds())
    }

    @Test
    fun `leaves threads open when the parents still have foreldreansvar`() {
        val decision =
            threadsToClose(
                listOf(oppdatering(barn, mor, far)),
                listOf(OpenThread("t-mor", barn, mor), OpenThread("t-far", barn, far)),
            )

        assertEquals(emptyList<String>(), decision.closedIds())
    }

    @Test
    fun `no open threads means nothing to close`() {
        assertEquals(CloseDecision(emptyList(), emptyList()), threadsToClose(listOf(oppdatering(barn)), emptyList()))
    }

    @Test
    fun `a thread without a parent ident is left open and counted`() {
        val utenForelder = OpenThread("t-ukjent", barn, null)

        val decision = threadsToClose(listOf(oppdatering(barn)), listOf(utenForelder, OpenThread("t-blank", barn, "")))

        assertEquals(emptyList<String>(), decision.closedIds())
        assertEquals(listOf("t-ukjent", "t-blank"), decision.withoutParent.map { it.id })
    }

    @Test
    fun `each thread is judged against its own child in a batch`() {
        val decision =
            threadsToClose(
                listOf(oppdatering(barn, mor), oppdatering(barn2, far)),
                listOf(
                    OpenThread("barn-mor", barn, mor),
                    OpenThread("barn-far", barn, far),
                    OpenThread("barn2-mor", barn2, mor),
                    OpenThread("barn2-far", barn2, far),
                    OpenThread("annet-barn", "30303012345", far),
                ),
            )

        assertEquals(listOf("barn-far", "barn2-mor"), decision.closedIds())
        assertEquals(listOf(barn, barn2), decision.toClose.map { it.oppdatering.barnIdent })
    }
}
