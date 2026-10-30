package no.nav.nks.foreldreansvar

import no.nav.nks.foreldreansvar.pdl.Adressebeskyttelse
import no.nav.nks.foreldreansvar.pdl.PersonBolkResultat

// Kode 6/7 rule: a parent with adressebeskyttelse cannot represent, and a child with adressebeskyttelse cannot be
// represented. Kept here so every endpoint uses the same rule. Never log the gradering together with an ident.

/** PDL gradering values that do not mean protection. Anything else, including values we don't know, counts as protected. */
private val UBESKYTTET = setOf("UGRADERT")

/**
 * True if the person has kode 6 or 7 (`STRENGT_FORTROLIG`, `STRENGT_FORTROLIG_UTLAND` or `FORTROLIG`).
 * Fails closed: an unknown or missing gradering on an entry counts as protected, so a new PDL value never gives access.
 */
fun List<Adressebeskyttelse>.erBeskyttet(): Boolean = any { it.gradering !in UBESKYTTET }

/** True if the person has kode 6 or 7. A result without person data also counts as protected (fail closed). */
fun PersonBolkResultat.erBeskyttet(): Boolean = person?.adressebeskyttelse?.erBeskyttet() ?: true
