package no.nav.nks.foreldreansvar.pdl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// Kotlin versions of the fields asked for in resources/pdl/person-bolk.graphql.
// If you add or remove fields in the query, update these classes to match.

/** One result per requested ident. `code` is e.g. `ok` or `not_found`; `person` is only set when `code` is `ok`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PersonBolkResultat(
    val ident: String,
    val code: String?,
    val person: BolkPerson?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BolkPerson(
    val adressebeskyttelse: List<Adressebeskyttelse> = emptyList(),
    val foedselsdato: List<Foedselsdato> = emptyList(),
    val navn: List<BolkNavn> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Foedselsdato(
    // ISO date (yyyy-MM-dd). May be missing when only the year is known.
    val foedselsdato: String?,
    val foedselsaar: Int?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BolkNavn(
    val fornavn: String?,
    val etternavn: String?,
)
