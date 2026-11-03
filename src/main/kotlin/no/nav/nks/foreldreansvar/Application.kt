package no.nav.nks.foreldreansvar

import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.nks.foreldreansvar.pdl.PdlClient
import java.time.Clock
import java.time.ZoneId

fun Application.module(
    azureConfig: AzureConfig,
    pdlClient: PdlClient,
    clock: Clock = Clock.system(ZoneId.of("Europe/Oslo")),
    isAlive: () -> Boolean = { true },
    meterRegistry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
) {
    // HTTP server metrics (ktor_http_server_requests_seconds) plus JVM, GC, thread and CPU metrics.
    install(MicrometerMetrics) {
        registry = meterRegistry
    }
    install(ContentNegotiation) {
        jackson()
    }
    configureErrorHandling()
    configureAuth(azureConfig)
    routing {
        internalRoutes(meterRegistry, isAlive)
        authenticate(AZURE_AUTH) {
            route("/api") {
                apiRoutes(pdlClient, clock)
            }
        }
    }
}
