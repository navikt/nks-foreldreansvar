package no.nav.nks.foreldreansvar

import no.nav.nks.foreldreansvar.pdl.Forelder
import no.nav.nks.foreldreansvar.pdl.Foreldreansvar
import no.nav.nks.foreldreansvar.pdl.PersonBolkResultat

// Request and response bodies for POST /api/foreldreansvar/verifiser (API contract with Salesforce).

/** Matches `NKS_ForeldreansvarService.VerifiserRequest` in crm-nks-integration. Nullable so a missing field gives our own 400. */
data class VerifiserRequest(
    val forelderIdent: String? = null,
    val barnIdent: String? = null,
)

data class VerifiserResponse(
    val kanRepresentere: Boolean,
)

data class ValidVerifiserRequest(
    val forelderIdent: String,
    val barnIdent: String,
)

fun VerifiserRequest.validated() =
    ValidVerifiserRequest(
        forelderIdent = requireIdent(forelderIdent, "forelderIdent"),
        barnIdent = requireIdent(barnIdent, "barnIdent"),
    )

/** The parent can represent the child if the child is an `ansvarssubjekt` in the parent's foreldreansvar. */
fun List<Foreldreansvar>.kanRepresentere(barnIdent: String) = any { it.ansvarssubjekt == barnIdent }

/** The outcome of a verifiser check. Only [OK] gives `kanRepresentere = true`. The name is safe to log: it has no ident. */
enum class Verifisering {
    OK,
    FORELDER_BESKYTTET,
    IKKE_FORELDREANSVAR,
    BARN_IKKE_FUNNET,
    BARN_BESKYTTET,
    BARN_DOED,
}

/**
 * Checks, in order: the parent has no kode 6/7, the child is in the parent's foreldreansvar, and the child is found in
 * PDL alive and without kode 6/7. [hentBarn] is only called when the parent checks pass, so we don't look up the child needlessly.
 */
suspend fun verifiser(
    forelder: Forelder,
    barnIdent: String,
    hentBarn: suspend (String) -> PersonBolkResultat?,
): Verifisering {
    if (forelder.adressebeskyttelse.erBeskyttet()) return Verifisering.FORELDER_BESKYTTET
    if (!forelder.foreldreansvar.kanRepresentere(barnIdent)) return Verifisering.IKKE_FORELDREANSVAR
    val barn = hentBarn(barnIdent) ?: return Verifisering.BARN_IKKE_FUNNET
    return when {
        barn.erBeskyttet() -> Verifisering.BARN_BESKYTTET
        barn.erDoed() -> Verifisering.BARN_DOED
        else -> Verifisering.OK
    }
}
