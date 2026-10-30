package no.nav.nks.foreldreansvar.pdl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// Kotlin versions of the fields asked for in resources/pdl/foreldreansvar.graphql.
// If you add or remove fields in the query, update these classes to match.

/** The parent from hentPerson: current adressebeskyttelse and foreldreansvar. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Forelder(
    // Empty when the person has no adressebeskyttelse. Only current values (historikk: false).
    val adressebeskyttelse: List<Adressebeskyttelse> = emptyList(),
    val foreldreansvar: List<Foreldreansvar> = emptyList(),
)

/** `gradering` is e.g. `FORTROLIG` (kode 7) or `STRENGT_FORTROLIG` (kode 6). See `erBeskyttet()`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Adressebeskyttelse(
    val gradering: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Foreldreansvar(
    val ansvar: String?,
    // Ident of the person with parental responsibility.
    val ansvarlig: String?,
    // Ident of the child.
    val ansvarssubjekt: String?,
    // Set instead of `ansvarlig` when the responsible person has no Norwegian ident.
    val ansvarligUtenIdentifikator: RelatertBiPerson?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RelatertBiPerson(
    val navn: Navn?,
    val foedselsdato: String?,
    val statsborgerskap: String?,
    val kjoenn: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Navn(
    val fornavn: String,
    val mellomnavn: String?,
    val etternavn: String,
)
