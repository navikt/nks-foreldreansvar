package no.nav.nks.foreldreansvar

import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import no.nav.nks.foreldreansvar.pdl.PdlClient

// pdlClient is not used by any route yet; the PDL endpoints will use it. Tests that do not need PDL can leave it out.
@Suppress("UNUSED_PARAMETER")
fun Application.module(
    azureConfig: AzureConfig,
    pdlClient: PdlClient? = null,
) {
    install(ContentNegotiation) {
        jackson()
    }
    configureAuth(azureConfig)
    routing {
        internalRoutes()
        authenticate(AZURE_AUTH) {
            route("/api") {
                apiRoutes()
            }
        }
    }
}
