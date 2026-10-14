package no.nav.nks.foreldreansvar

import no.nav.nks.foreldreansvar.pdl.Foreldreansvar

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
