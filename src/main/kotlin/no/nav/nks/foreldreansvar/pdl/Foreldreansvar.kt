package no.nav.nks.foreldreansvar.pdl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// Kotlin versions of the fields asked for in resources/pdl/foreldreansvar.graphql.
// If you add or remove fields in the query, update these classes to match.

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
