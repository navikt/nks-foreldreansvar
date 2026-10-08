package no.nav.nks.foreldreansvar

import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.internalRoutes() {
    route("/internal") {
        get("/isalive") { call.respondText("ALIVE") }
        get("/isready") { call.respondText("READY") }
    }
}
