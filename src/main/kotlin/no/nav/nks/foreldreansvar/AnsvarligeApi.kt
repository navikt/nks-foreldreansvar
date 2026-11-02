package no.nav.nks.foreldreansvar

import no.nav.nks.foreldreansvar.pdl.Barn
import no.nav.nks.foreldreansvar.pdl.PdlClient
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

// Request and response bodies for POST /api/foreldreansvar/ansvarlige (API contract with Salesforce), and the shared
// lookup hentAnsvarlige() that the Kafka pipeline will reuse. Event = signal, PDL = truth: we always return the full
// current state for the child, and an empty list means "remove all sharing".

private val log = LoggerFactory.getLogger("no.nav.nks.foreldreansvar.Ansvarlige")

/** The child's ident. Nullable so a missing field gives our own 400. */
data class AnsvarligeRequest(
    val barnIdent: String? = null,
)

fun AnsvarligeRequest.validBarnIdent(): String = requireIdent(barnIdent, "barnIdent")

/** `hentetTidspunkt` is when the PDL lookup started, as ISO-8601 in UTC (e.g. `2026-10-29T10:00:00Z`). */
data class AnsvarligeResponse(
    val barnIdent: String,
    val ansvarlige: List<AnsvarligDto>,
    val hentetTidspunkt: String,
)

/** `ansvar` is passed on as PDL returns it, e.g. `felles`, `mor`, `far`, `medmor` or `andre`. */
data class AnsvarligDto(
    val ident: String,
    val ansvar: String?,
)

/** Which parent roles in forelderBarnRelasjon an `ansvar` points to when PDL has no `ansvarlig` ident. */
private val ROLLER_FOR_ANSVAR =
    mapOf(
        "mor" to setOf("MOR"),
        "far" to setOf("FAR"),
        "medmor" to setOf("MEDMOR"),
        "felles" to setOf("MOR", "FAR", "MEDMOR"),
    )

/**
 * The people with foreldreansvar for the child, each once (first `ansvar` wins), before the kode 6/7 check on them.
 * Uses `ansvarlig` when set. Otherwise `mor`/`far`/`medmor`/`felles` are resolved from the child's parents in
 * forelderBarnRelasjon (never `BARN` relations, which are the child's own children). Entries that give no ident
 * (e.g. `andre`/`ukjent` without `ansvarlig`, or only `ansvarligUtenIdentifikator`) are skipped.
 */
fun Barn.ansvarligeKandidater(barnIdent: String): List<AnsvarligDto> =
    foreldreansvar
        .filter { it.ansvarssubjekt == null || it.ansvarssubjekt == barnIdent }
        .flatMap { entry ->
            if (entry.ansvarlig != null) {
                listOf(AnsvarligDto(entry.ansvarlig, entry.ansvar))
            } else {
                val roller = ROLLER_FOR_ANSVAR[entry.ansvar?.lowercase()].orEmpty()
                forelderBarnRelasjon
                    .filter { it.relatertPersonsRolle in roller }
                    .mapNotNull { it.relatertPersonsIdent }
                    .map { AnsvarligDto(it, entry.ansvar) }
            }
        }.filter { it.ident != barnIdent }
        .distinctBy { it.ident }

/**
 * The full current list of people with foreldreansvar for [barnIdent] that Salesforce may share the child with.
 * Kode 6/7, fail closed: a protected child gives an empty list (and the parents are not looked up), and protected
 * parents, or parents PDL does not return, are left out. Throws [no.nav.nks.foreldreansvar.pdl.PdlException.NotFound]
 * if the child does not exist in PDL.
 */
suspend fun hentAnsvarlige(
    pdlClient: PdlClient,
    barnIdent: String,
    clock: Clock,
): AnsvarligeResponse {
    val hentetTidspunkt = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS).toString()
    val barn = pdlClient.hentBarn(barnIdent)
    if (barn.adressebeskyttelse.erBeskyttet()) {
        log.info("ansvarlige: child has adressebeskyttelse, returning no ansvarlige")
        return AnsvarligeResponse(barnIdent, emptyList(), hentetTidspunkt)
    }
    val kandidater = barn.ansvarligeKandidater(barnIdent)
    val personer = pdlClient.hentPersonBolk(kandidater.map { it.ident }, historikk = false).associateBy { it.ident }
    val ansvarlige = kandidater.filter { personer[it.ident]?.erBeskyttet() == false }
    if (ansvarlige.size < kandidater.size) {
        log.info("ansvarlige: left out {} ansvarlige with adressebeskyttelse or not found", kandidater.size - ansvarlige.size)
    }
    return AnsvarligeResponse(barnIdent, ansvarlige, hentetTidspunkt)
}
