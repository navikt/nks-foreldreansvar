package no.nav.nks.foreldreansvar

import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import no.nav.nks.foreldreansvar.pdl.PdlClient
import java.time.Clock
import java.time.ZoneId

fun Application.module(
    azureConfig: AzureConfig,
    pdlClient: PdlClient,
    clock: Clock = Clock.system(ZoneId.of("Europe/Oslo")),
) {
    install(ContentNegotiation) {
        jackson()
    }
    configureErrorHandling()
    configureAuth(azureConfig)
    routing {
        internalRoutes()
        authenticate(AZURE_AUTH) {
            route("/api") {
                apiRoutes(pdlClient, clock)
            }
        }
    }
}
