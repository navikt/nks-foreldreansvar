package no.nav.nks.foreldreansvar

import kotlinx.coroutines.runBlocking
import no.nav.nks.foreldreansvar.pdl.Adressebeskyttelse
import no.nav.nks.foreldreansvar.pdl.BolkPerson
import no.nav.nks.foreldreansvar.pdl.Doedsfall
import no.nav.nks.foreldreansvar.pdl.Forelder
import no.nav.nks.foreldreansvar.pdl.Foreldreansvar
import no.nav.nks.foreldreansvar.pdl.PersonBolkResultat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdressebeskyttelseTest {
    private fun graderinger(vararg values: String?) = values.map { Adressebeskyttelse(it) }

    @Test
    fun `kode 6 and 7 are protected`() {
        for (gradering in BESKYTTET) {
            assertTrue(graderinger(gradering).erBeskyttet(), gradering)
        }
    }

    @Test
    fun `no adressebeskyttelse or UGRADERT is not protected`() {
        assertFalse(emptyList<Adressebeskyttelse>().erBeskyttet())
        assertFalse(graderinger("UGRADERT").erBeskyttet())
    }

    @Test
    fun `unknown or missing gradering counts as protected`() {
        assertTrue(graderinger("NY_VERDI").erBeskyttet())
        assertTrue(graderinger(null).erBeskyttet())
    }

    @Test
    fun `any protected entry makes the person protected`() {
        assertTrue(graderinger("UGRADERT", "FORTROLIG").erBeskyttet())
    }

    @Test
    fun `bolk result without person data counts as protected`() {
        assertTrue(PersonBolkResultat("10101012345", "ok", null).erBeskyttet())
        assertFalse(bolk("UGRADERT").erBeskyttet())
        assertTrue(bolk("STRENGT_FORTROLIG").erBeskyttet())
    }

    @Test
    fun `verifiser checks the parent before looking up the child`() {
        var lookups = 0
        val resultat =
            runBlocking {
                verifiser(forelder("FORTROLIG"), BARN) {
                    lookups++
                    bolk("UGRADERT")
                }
            }
        assertEquals(Verifisering.FORELDER_BESKYTTET, resultat)
        assertEquals(0, lookups)
    }

    @Test
    fun `verifiser gives the reason for each outcome`() {
        runBlocking {
            assertEquals(Verifisering.OK, verifiser(forelder(), BARN) { bolk("UGRADERT") })
            assertEquals(Verifisering.IKKE_FORELDREANSVAR, verifiser(forelder(), "20202012345") { error("not called") })
            assertEquals(Verifisering.BARN_IKKE_FUNNET, verifiser(forelder(), BARN) { null })
            assertEquals(Verifisering.BARN_BESKYTTET, verifiser(forelder(), BARN) { bolk("STRENGT_FORTROLIG_UTLAND") })
            val doed = PersonBolkResultat(BARN, "ok", BolkPerson(doedsfall = listOf(Doedsfall(null))))
            assertEquals(Verifisering.BARN_DOED, verifiser(forelder(), BARN) { doed })
        }
    }

    private fun forelder(vararg gradering: String) =
        Forelder(
            adressebeskyttelse = graderinger(*gradering),
            foreldreansvar = listOf(Foreldreansvar("felles", "01020312345", BARN, null)),
        )

    private fun bolk(gradering: String) = PersonBolkResultat(BARN, "ok", BolkPerson(adressebeskyttelse = graderinger(gradering)))

    private companion object {
        const val BARN = "10101012345"
    }
}
