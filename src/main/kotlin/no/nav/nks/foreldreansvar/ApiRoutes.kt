package no.nav.nks.foreldreansvar

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Routes under /api. Every route here requires a valid Entra ID M2M token (see [configureAuth]).
 */
fun Route.apiRoutes() {
    // Temporary endpoint to verify auth end-to-end until the real PDL endpoints exist.
    get("/ping") { call.respondText("pong") }
}
