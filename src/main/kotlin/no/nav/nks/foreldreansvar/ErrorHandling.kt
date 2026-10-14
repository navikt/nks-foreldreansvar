package no.nav.nks.foreldreansvar

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import no.nav.nks.foreldreansvar.pdl.PdlException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("no.nav.nks.foreldreansvar.ErrorHandling")
private val problemMapper = jacksonObjectMapper()

/** Thrown by routes when the request body is invalid. [detail] is sent to the caller, so it must never contain an ident. */
class InvalidRequestException(
    val detail: String,
) : RuntimeException(detail)

/** Error body in RFC 7807 format ("problem details"). */
data class Problem(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String,
)

/**
 * Turns exceptions into RFC 7807 responses, so routes can just throw.
 *
 * We always send a fixed `detail` text and never `exception.message`: some exceptions (e.g. JSON parse errors)
 * can contain the request body, and the request body contains an ident. For the same reason we only log the
 * exception type, not the message.
 */
fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<InvalidRequestException> { call, cause ->
            call.respondProblem(HttpStatusCode.BadRequest, cause.detail)
        }
        exception<BadRequestException> { call, _ ->
            call.respondProblem(HttpStatusCode.BadRequest, "Request body must be valid JSON")
        }
        exception<PdlException> { call, cause ->
            val (status, detail) = cause.toHttp()
            if (status.value >= 500) {
                log.warn("PDL call failed: {} -> HTTP {}", cause::class.simpleName, status.value)
            } else {
                log.info("PDL call failed: {} -> HTTP {}", cause::class.simpleName, status.value)
            }
            call.respondProblem(status, detail)
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled exception: {}", cause::class.qualifiedName)
            call.respondProblem(HttpStatusCode.InternalServerError, "Internal error")
        }
    }
}

private fun PdlException.toHttp(): Pair<HttpStatusCode, String> =
    when (this) {
        is PdlException.NotFound -> HttpStatusCode.NotFound to "Person not found in PDL"

        is PdlException.BadRequest -> HttpStatusCode.BadRequest to "PDL rejected the request as invalid"

        // PDL rejected *our* token or access. The caller did nothing wrong, so this is a bad upstream response, not 401/403.
        is PdlException.Unauthorized -> HttpStatusCode.BadGateway to "PDL denied access for this app"

        // Texas is our own sidecar, so failing to get a token is an internal error in this app.
        is PdlException.TokenUnavailable -> HttpStatusCode.InternalServerError to "Could not get a token for PDL"

        is PdlException.Timeout -> HttpStatusCode.GatewayTimeout to "PDL did not answer in time"

        is PdlException.Unexpected -> HttpStatusCode.BadGateway to "Unexpected response from PDL"
    }

private suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    detail: String,
) {
    val problem = Problem(title = status.description, status = status.value, detail = detail)
    respondText(problemMapper.writeValueAsString(problem), ContentType.Application.ProblemJson, status)
}
