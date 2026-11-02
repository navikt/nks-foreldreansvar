package no.nav.nks.foreldreansvar.pdl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// Kotlin versions of the fields asked for in resources/pdl/barn-ansvarlige.graphql.
// If you add or remove fields in the query, update these classes to match.

/** The child from hentPerson: current adressebeskyttelse and foreldreansvar, and the child's parents. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Barn(
    // Empty when the person has no adressebeskyttelse. Only current values (historikk: false).
    val adressebeskyttelse: List<Adressebeskyttelse> = emptyList(),
    // Only current values (historikk: false). `ansvarligUtenIdentifikator` is not asked for here.
    val foreldreansvar: List<Foreldreansvar> = emptyList(),
    val forelderBarnRelasjon: List<ForelderBarnRelasjon> = emptyList(),
)

/** `relatertPersonsRolle` is `MOR`, `FAR`, `MEDMOR` (a parent of the child) or `BARN` (a child of the child). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ForelderBarnRelasjon(
    val relatertPersonsIdent: String?,
    val relatertPersonsRolle: String?,
)
