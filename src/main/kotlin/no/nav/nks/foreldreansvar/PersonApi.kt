package no.nav.nks.foreldreansvar

import no.nav.nks.foreldreansvar.pdl.Foreldreansvar
import no.nav.nks.foreldreansvar.pdl.RelatertBiPerson

// Request and response bodies for POST /api/person. These are our API contract with Salesforce and are kept
// separate from the PDL classes, so changing the PDL query does not silently change what Salesforce receives.

/** Matches `NKS_ForeldreansvarService.PersonRequest` in crm-nks-integration. Nullable so a missing field gives our own 400. */
data class PersonRequest(
    val ident: String? = null,
)

data class PersonResponse(
    val foreldreansvar: List<ForeldreansvarDto>,
)

data class ForeldreansvarDto(
    val ansvar: String?,
    val ansvarlig: String?,
    val ansvarssubjekt: String?,
    val ansvarligUtenIdentifikator: PersonUtenIdentDto?,
)

data class PersonUtenIdentDto(
    val navn: NavnDto?,
    val foedselsdato: String?,
    val statsborgerskap: String?,
    val kjoenn: String?,
)

data class NavnDto(
    val fornavn: String,
    val mellomnavn: String?,
    val etternavn: String,
)

private val IDENT_REGEX = Regex("^\\d{11}$")

/**
 * Returns the ident or throws [InvalidRequestException]. Only checks the format (11 digits): synthetic test idents
 * do not always pass the mod11 checksum, and PDL validates the ident itself.
 */
fun PersonRequest.validIdent(): String {
    val ident = ident?.trim()
    if (ident.isNullOrEmpty()) throw InvalidRequestException("ident is required")
    if (!IDENT_REGEX.matches(ident)) throw InvalidRequestException("ident must be 11 digits")
    return ident
}

fun List<Foreldreansvar>.toResponse() = PersonResponse(map { it.toDto() })

private fun Foreldreansvar.toDto() =
    ForeldreansvarDto(
        ansvar = ansvar,
        ansvarlig = ansvarlig,
        ansvarssubjekt = ansvarssubjekt,
        ansvarligUtenIdentifikator = ansvarligUtenIdentifikator?.toDto(),
    )

private fun RelatertBiPerson.toDto() =
    PersonUtenIdentDto(
        navn = navn?.let { NavnDto(it.fornavn, it.mellomnavn, it.etternavn) },
        foedselsdato = foedselsdato,
        statsborgerskap = statsborgerskap,
        kjoenn = kjoenn,
    )
