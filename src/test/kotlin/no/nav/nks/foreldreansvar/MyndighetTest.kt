package no.nav.nks.foreldreansvar

import no.nav.nks.foreldreansvar.pdl.Foedselsdato
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MyndighetTest {
    private val today = LocalDate.parse("2026-10-29")

    private fun dato(foedselsdato: String?) = listOf(Foedselsdato(foedselsdato, foedselsdato?.take(4)?.toIntOrNull()))

    private fun aar(foedselsaar: Int?) = listOf(Foedselsdato(null, foedselsaar))

    @Test
    fun `full foedselsdato decides, and the 18th birthday counts as myndig`() {
        assertFalse(dato("2008-10-30").regnesSomMyndig(today), "day before the 18th birthday")
        assertTrue(dato("2008-10-29").regnesSomMyndig(today), "18th birthday")
        assertTrue(dato("2000-01-01").regnesSomMyndig(today))
        assertFalse(dato("2026-10-29").regnesSomMyndig(today), "born today")
    }

    @Test
    fun `born on 29 February turns 18 on 1 March in a non-leap year`() {
        assertFalse(dato("2008-02-29").regnesSomMyndig(LocalDate.parse("2026-02-28")))
        assertTrue(dato("2008-02-29").regnesSomMyndig(LocalDate.parse("2026-03-01")))
    }

    @Test
    fun `foedselsaar alone is used only when the answer is certain`() {
        assertFalse(aar(2009).regnesSomMyndig(today), "at most 17")
        assertTrue(aar(2008).regnesSomMyndig(today), "17 or 18, ambiguous")
        assertTrue(aar(2007).regnesSomMyndig(today), "at least 18")
    }

    @Test
    fun `no usable birth data counts as myndig`() {
        assertTrue(emptyList<Foedselsdato>().regnesSomMyndig(today))
        assertTrue(aar(null).regnesSomMyndig(today))
        assertTrue(listOf(Foedselsdato("ikke-en-dato", null)).regnesSomMyndig(today))
    }

    @Test
    fun `an unparseable foedselsdato falls back to foedselsaar`() {
        assertFalse(listOf(Foedselsdato("ikke-en-dato", 2015)).regnesSomMyndig(today))
    }

    @Test
    fun `idag uses the date in Oslo`() {
        val clock = Clock.fixed(Instant.parse("2026-10-28T23:30:00Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.parse("2026-10-29"), idag(clock))
    }
}
