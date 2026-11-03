package no.nav.nks.foreldreansvar.salesforce

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer

/**
 * Times one HTTP call to Salesforce as `salesforce_requests_seconds{operation, outcome}`.
 * `operation`: `token` or `send`. `outcome`: `ok`, `unauthorized`, `client_error`, `server_error`, `timeout` or `error`.
 */
internal suspend fun MeterRegistry.timeSalesforce(
    operation: String,
    call: suspend () -> HttpResponse,
): HttpResponse {
    val sample = Timer.start(this)
    var outcome = "error"
    try {
        return call().also { outcome = outcomeOf(it.status) }
    } catch (e: Exception) {
        outcome =
            when (e) {
                is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException -> "timeout"
                else -> "error"
            }
        throw e
    } finally {
        sample.stop(timer("salesforce.requests", "operation", operation, "outcome", outcome))
    }
}

private fun outcomeOf(status: HttpStatusCode): String =
    when {
        status.value in 200..299 -> "ok"
        status == HttpStatusCode.Unauthorized -> "unauthorized"
        status.value in 400..499 -> "client_error"
        status.value >= 500 -> "server_error"
        else -> "error"
    }
