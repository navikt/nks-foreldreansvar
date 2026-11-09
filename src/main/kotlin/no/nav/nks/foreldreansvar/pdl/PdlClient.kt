package no.nav.nks.foreldreansvar.pdl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.jackson.jackson
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.nks.foreldreansvar.TEAM_LOGS
import no.nav.nks.foreldreansvar.erBeskyttet
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Creates the HTTP client used for Texas and PDL. CIO is Ktor's own pure-Kotlin engine; tests pass a MockEngine.
 */
fun createHttpClient(
    engine: HttpClientEngine = CIO.create(),
    timeoutMillis: Long = 10_000,
): HttpClient =
    HttpClient(engine) {
        install(ContentNegotiation) { jackson() }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = 5_000
        }
    }

/**
 * Client for the PDL GraphQL API. Throws [PdlException] on every kind of failure.
 *
 * Idents are personal data: never log them or put them in exception messages. The only exception is log lines
 * marked [TEAM_LOGS], which go to Team Logs and never to stdout.
 *
 * Each call is timed as `pdl_requests_seconds{operation, outcome}`, e.g. operation=hentBarn, outcome=not_found.
 */
class PdlClient(
    private val httpClient: HttpClient,
    private val config: PdlConfig,
    private val tokenClient: TexasTokenClient,
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
) {
    private val log = LoggerFactory.getLogger(PdlClient::class.java)

    /** Looks up the parent's adressebeskyttelse and foreldreansvar. [historikk] applies to foreldreansvar only. */
    suspend fun hentForelder(
        ident: String,
        historikk: Boolean = false,
    ): Forelder =
        timed("hentForelder") {
            val request = GraphQLRequest(FORELDREANSVAR_QUERY, mapOf("ident" to ident, "historikk" to historikk))
            val response = callPdl(request)
            val forelder = response.data?.hentPerson
            if (forelder == null) {
                log.info(TEAM_LOGS, "PDL hentForelder ident={}: not found", ident)
                throw PdlException.NotFound()
            }
            log.info(
                TEAM_LOGS,
                "PDL hentForelder ident={} historikk={}: found, beskyttet={}, ansvarssubjekter={}",
                ident,
                historikk,
                forelder.adressebeskyttelse.erBeskyttet(),
                forelder.foreldreansvar.map { it.ansvarssubjekt },
            )
            forelder
        }

    /** Looks up the child's adressebeskyttelse, foreldreansvar and parents (all current values). */
    suspend fun hentBarn(ident: String): Barn =
        timed("hentBarn") {
            val request = GraphQLRequest(BARN_ANSVARLIGE_QUERY, mapOf("ident" to ident))
            val barn = callPdl(request).data?.hentBarn
            if (barn == null) {
                log.info(TEAM_LOGS, "PDL hentBarn ident={}: not found", ident)
                throw PdlException.NotFound()
            }
            log.info(
                TEAM_LOGS,
                "PDL hentBarn ident={}: found, beskyttet={}, foreldreansvar={}, foreldre={}",
                ident,
                barn.adressebeskyttelse.erBeskyttet(),
                barn.foreldreansvar.map { "${it.ansvar}:${it.ansvarlig}" },
                barn.forelderBarnRelasjon
                    .filter { it.relatertPersonsRolle != "BARN" }
                    .map { "${it.relatertPersonsRolle}:${it.relatertPersonsIdent}" },
            )
            barn
        }

    /**
     * Looks up several persons in one call. Returns only results with `code == "ok"`; the rest are left out and
     * only their codes are logged. hentPersonBolk is only available with a system token (Texas M2M), not TokenX/OBO.
     */
    suspend fun hentPersonBolk(
        identer: List<String>,
        historikk: Boolean = false,
    ): List<PersonBolkResultat> {
        if (identer.isEmpty()) return emptyList()
        val request = GraphQLRequest(PERSON_BOLK_QUERY, mapOf("identer" to identer, "historikk" to historikk))
        val resultater = timed("hentPersonBolk") { callPdl(request).data?.hentPersonBolk.orEmpty() }
        val (ok, ikkeOk) = resultater.partition { it.code == "ok" && it.person != null }
        log.info(
            TEAM_LOGS,
            "PDL hentPersonBolk identer={}: {}",
            identer,
            resultater.map { it.teamLogsSummary() },
        )
        if (ikkeOk.isNotEmpty()) {
            // Only codes and counts: never log the idents.
            log.info(
                "hentPersonBolk left out {} of {} results, codes {}",
                ikkeOk.size,
                resultater.size,
                ikkeOk.groupingBy { it.code }.eachCount(),
            )
        }
        return ok
    }

    private fun PersonBolkResultat.teamLogsSummary(): String {
        val person = person ?: return "$ident:$code"
        return "$ident:$code:beskyttet=${erBeskyttet()}:doed=${person.doedsfall.isNotEmpty()}"
    }

    private suspend fun <T> timed(
        operation: String,
        block: suspend () -> T,
    ): T {
        val sample = Timer.start(meterRegistry)
        var outcome = "ok"
        try {
            return block()
        } catch (e: Exception) {
            outcome = outcomeOf(e)
            throw e
        } finally {
            sample.stop(meterRegistry.timer("pdl.requests", "operation", operation, "outcome", outcome))
        }
    }

    private fun outcomeOf(e: Exception): String =
        when (e) {
            is PdlException.NotFound -> "not_found"
            is PdlException.Unauthorized -> "unauthorized"
            is PdlException.BadRequest -> "bad_request"
            is PdlException.TokenUnavailable -> "token_unavailable"
            is PdlException.Timeout -> "timeout"
            else -> "error"
        }

    private suspend fun callPdl(request: GraphQLRequest): PdlResponse {
        try {
            var response = post(request, tokenClient.token(config.target))
            if (response.status == HttpStatusCode.Unauthorized) {
                // The cached token may be stale (e.g. our access just changed). Get a fresh one and try once more.
                log.info("PDL returned 401, retrying once with a new token")
                response = post(request, tokenClient.token(config.target, skipCache = true))
            }
            return parse(response)
        } catch (e: PdlException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw PdlException.Timeout(e)
        } catch (e: ConnectTimeoutException) {
            throw PdlException.Timeout(e)
        } catch (e: SocketTimeoutException) {
            throw PdlException.Timeout(e)
        } catch (e: IOException) {
            throw PdlException.Unexpected("Network error calling PDL", e)
        }
    }

    private suspend fun post(
        request: GraphQLRequest,
        token: String,
    ): HttpResponse =
        httpClient.post(config.url) {
            bearerAuth(token)
            // Tells PDL which registered processing purpose this lookup falls under.
            header("Behandlingsnummer", config.behandlingsnummer)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun parse(response: HttpResponse): PdlResponse {
        val status = response.status
        when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden -> {
                throw PdlException.Unauthorized("PDL returned HTTP ${status.value}")
            }

            !status.isSuccess() -> {
                log.warn("PDL returned HTTP {}", status.value)
                throw PdlException.Unexpected("PDL returned HTTP ${status.value}")
            }
        }

        val body =
            try {
                response.body<PdlResponse>()
            } catch (e: Exception) {
                throw PdlException.Unexpected("Could not read PDL response", e)
            }

        // PDL returns HTTP 200 with an `errors` array for GraphQL-level errors.
        val codes = body.errors.orEmpty().map { it.extensions?.code ?: "unknown" }
        if (codes.isNotEmpty()) {
            // Only log the error codes: PDL messages may contain personal data.
            log.warn("PDL returned GraphQL errors with codes {}", codes)
            throw when {
                "not_found" in codes -> {
                    PdlException.NotFound()
                }

                "unauthenticated" in codes || "unauthorized" in codes -> {
                    PdlException.Unauthorized("PDL returned error codes $codes")
                }

                "bad_request" in codes -> {
                    PdlException.BadRequest("PDL returned error codes $codes")
                }

                else -> {
                    PdlException.Unexpected("PDL returned error codes $codes")
                }
            }
        }
        return body
    }

    private data class GraphQLRequest(
        val query: String,
        val variables: Map<String, Any>,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class PdlResponse(
        val data: Data?,
        val errors: List<GraphQLError>?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Data(
        val hentPerson: Forelder? = null,
        val hentPersonBolk: List<PersonBolkResultat>? = null,
        // Alias for hentPerson in barn-ansvarlige.graphql.
        val hentBarn: Barn? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GraphQLError(
        val extensions: Extensions?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Extensions(
        val code: String?,
    )

    private companion object {
        val FORELDREANSVAR_QUERY: String =
            requireNotNull(PdlClient::class.java.getResource("/pdl/foreldreansvar.graphql")) {
                "Missing resource /pdl/foreldreansvar.graphql"
            }.readText()

        val PERSON_BOLK_QUERY: String =
            requireNotNull(PdlClient::class.java.getResource("/pdl/person-bolk.graphql")) {
                "Missing resource /pdl/person-bolk.graphql"
            }.readText()

        val BARN_ANSVARLIGE_QUERY: String =
            requireNotNull(PdlClient::class.java.getResource("/pdl/barn-ansvarlige.graphql")) {
                "Missing resource /pdl/barn-ansvarlige.graphql"
            }.readText()
    }
}
