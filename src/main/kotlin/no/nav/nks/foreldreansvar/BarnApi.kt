package no.nav.nks.foreldreansvar

import no.nav.nks.foreldreansvar.pdl.Foreldreansvar
import no.nav.nks.foreldreansvar.pdl.PersonBolkResultat
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeParseException

// Request and response bodies for POST /api/foreldreansvar/barn. These are our API contract with Salesforce and are
// kept separate from the PDL classes, so changing a PDL query does not silently change what Salesforce receives.

/** The parent's ident. Nullable so a missing field gives our own 400. */
data class BarnRequest(
    val ident: String? = null,
)

data class BarnResponse(
    val barn: List<BarnDto>,
)

/**
 * Name and birth data are passed on as PDL returns them, `null` if missing. `alder` is whole years calculated from
 * `foedselsdato`, and `null` without it: `foedselsaar` alone could give an age that is one year too high.
 */
data class BarnDto(
    val ident: String,
    val fornavn: String?,
    val etternavn: String?,
    val foedselsdato: String?,
    val foedselsaar: Int?,
    val alder: Int?,
)

private val IDENT_REGEX = Regex("^\\d{11}$")

/**
 * Returns the trimmed ident or throws [InvalidRequestException]. Only checks the format (11 digits): synthetic test
 * idents do not always pass the mod11 checksum, and PDL validates the ident itself. The error names the field, never the value.
 */
fun requireIdent(
    value: String?,
    fieldName: String,
): String {
    val ident = value?.trim()
    if (ident.isNullOrEmpty()) throw InvalidRequestException("$fieldName is required")
    if (!IDENT_REGEX.matches(ident)) throw InvalidRequestException("$fieldName must be 11 digits")
    return ident
}

fun BarnRequest.validIdent(): String = requireIdent(ident, "ident")

/** The children (`ansvarssubjekt`) in the parent's foreldreansvar, each once, in order, without the parent's own ident. */
fun List<Foreldreansvar>.barnIdenter(forelderIdent: String): List<String> =
    mapNotNull { it.ansvarssubjekt }.filter { it != forelderIdent }.distinct()

/**
 * Builds the response in the order of [identer]. Children PDL did not return (e.g. `not_found`) and children with
 * adressebeskyttelse (kode 6/7) are left out. [today] is used to calculate `alder`.
 */
fun barnResponse(
    identer: List<String>,
    personer: List<PersonBolkResultat>,
    today: LocalDate,
): BarnResponse {
    val byIdent = personer.associateBy { it.ident }
    return BarnResponse(identer.mapNotNull { ident -> byIdent[ident]?.takeUnless { it.erBeskyttet() }?.toDto(today) })
}

/** Whole years from [foedselsdato] (`yyyy-MM-dd`) to [today], or `null` if missing or unparseable. */
fun alder(
    foedselsdato: String?,
    today: LocalDate,
): Int? {
    val dato =
        try {
            foedselsdato?.let(LocalDate::parse)
        } catch (_: DateTimeParseException) {
            null
        } ?: return null
    return Period.between(dato, today).years.takeIf { it >= 0 }
}

private fun PersonBolkResultat.toDto(today: LocalDate): BarnDto {
    val navn = person?.navn?.firstOrNull()
    val foedsel = person?.foedselsdato?.firstOrNull()
    return BarnDto(
        ident = ident,
        fornavn = navn?.fornavn,
        etternavn = navn?.etternavn,
        foedselsdato = foedsel?.foedselsdato,
        foedselsaar = foedsel?.foedselsaar,
        alder = alder(foedsel?.foedselsdato, today),
    )
}
