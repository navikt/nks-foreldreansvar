package no.nav.nks.foreldreansvar

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import no.nav.nks.foreldreansvar.pdl.PdlClient

/**
 * Routes under /api. Every route here requires a valid Entra ID M2M token (see [configureAuth]).
 * Errors are thrown and turned into problem-details responses by [configureErrorHandling].
 */
fun Route.apiRoutes(pdlClient: PdlClient) {
    // Temporary endpoint to verify auth end-to-end.
    get("/ping") { call.respondText("pong") }

    // Returns who has parental responsibility for/of the person right now (historikk = false).
    post("/person") {
        val ident = call.receive<PersonRequest>().validIdent()
        val foreldreansvar = pdlClient.hentForeldreansvar(ident, historikk = false)
        call.respond(foreldreansvar.toResponse())
    }
}
