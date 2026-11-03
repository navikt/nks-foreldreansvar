package no.nav.nks.foreldreansvar

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * Nais restarts the pod when isalive fails, and stops sending traffic while isready fails.
 * [isAlive] covers background work such as the Leesah consumer. isready only reflects the HTTP API,
 * so a Kafka problem doesn't take the API out of rotation.
 * Nais scrapes /internal/metrics (Prometheus text format). These routes are not behind Entra auth.
 */
fun Route.internalRoutes(
    meterRegistry: PrometheusMeterRegistry,
    isAlive: () -> Boolean = { true },
) {
    route("/internal") {
        get("/isalive") {
            if (isAlive()) {
                call.respondText("ALIVE")
            } else {
                call.respondText("NOT ALIVE", status = HttpStatusCode.ServiceUnavailable)
            }
        }
        get("/isready") { call.respondText("READY") }
        get("/metrics") { call.respondText(meterRegistry.scrape(), PROMETHEUS_CONTENT_TYPE) }
    }
}

private val PROMETHEUS_CONTENT_TYPE = ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
