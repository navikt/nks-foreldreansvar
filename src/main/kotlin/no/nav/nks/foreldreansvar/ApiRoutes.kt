package no.nav.nks.foreldreansvar

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import no.nav.nks.foreldreansvar.pdl.PdlClient
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate

private val log = LoggerFactory.getLogger("no.nav.nks.foreldreansvar.ApiRoutes")

/**
 * Routes under /api. Every route here requires a valid Entra ID M2M token (see [configureAuth]).
 * Errors are thrown and turned into problem-details responses by [configureErrorHandling].
 */
fun Route.apiRoutes(
    pdlClient: PdlClient,
    clock: Clock,
) {
    // Returns the children the parent has foreldreansvar for right now (historikk = false), with name, birth data and age.
    // Kode 6/7: a protected parent gets an empty list, and protected children are left out.
    post("/foreldreansvar/barn") {
        val forelderIdent = call.receive<BarnRequest>().validIdent()
        val forelder = pdlClient.hentForelder(forelderIdent, historikk = false)
        if (forelder.adressebeskyttelse.erBeskyttet()) {
            // Same response as "no children", so the answer does not reveal that the parent is protected.
            log.info("barn: parent has adressebeskyttelse, returning no children")
            call.respond(BarnResponse(emptyList()))
            return@post
        }
        val identer = forelder.foreldreansvar.barnIdenter(forelderIdent)
        val personer = if (identer.isEmpty()) emptyList() else pdlClient.hentPersonBolk(identer, historikk = false)
        val beskyttede = personer.count { it.erBeskyttet() }
        if (beskyttede > 0) log.info("barn: left out {} children with adressebeskyttelse", beskyttede)
        call.respond(barnResponse(identer, personer, LocalDate.now(clock)))
    }

    // Answers whether the parent can represent the child: the child must be in the parent's current foreldreansvar,
    // and neither of them can have kode 6/7. Salesforce relies on this as its backend check.
    post("/foreldreansvar/verifiser") {
        val request = call.receive<VerifiserRequest>().validated()
        val forelder = pdlClient.hentForelder(request.forelderIdent, historikk = false)
        val resultat =
            verifiser(forelder, request.barnIdent) { barnIdent ->
                pdlClient.hentPersonBolk(listOf(barnIdent), historikk = false).singleOrNull { it.ident == barnIdent }
            }
        if (resultat != Verifisering.OK) log.info("verifiser: kanRepresentere=false, reason {}", resultat)
        call.respond(VerifiserResponse(kanRepresentere = resultat == Verifisering.OK))
    }
}
