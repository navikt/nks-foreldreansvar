package no.nav.nks.foreldreansvar

import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.module(azureConfig: AzureConfig) {
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
