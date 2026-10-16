package no.nav.nks.foreldreansvar

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import no.nav.nks.foreldreansvar.pdl.PdlClient
import no.nav.nks.foreldreansvar.pdl.PdlConfig
import no.nav.nks.foreldreansvar.pdl.TexasTokenClient
import no.nav.nks.foreldreansvar.pdl.createHttpClient

private val testPdlConfig =
    PdlConfig(
        url = "http://pdl.test/graphql",
        target = "dev-fss:pdl:pdl-api",
        behandlingsnummer = "B118",
        tokenEndpoint = "http://texas.test/api/v1/token",
    )

/**
 * A real [PdlClient] whose HTTP calls go to fake Texas and PDL servers. Records the bodies sent to PDL.
 * [pdl] answers hentForeldreansvar, [bolk] answers hentPersonBolk.
 */
class FakePdl(
    timeoutMillis: Long = 10_000,
    private val texasStatus: HttpStatusCode = HttpStatusCode.OK,
    private val bolk: suspend MockRequestHandleScope.(
        HttpRequestData,
    ) -> HttpResponseData = { error("hentPersonBolk should not be called") },
    private val pdl: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val pdlBodies = mutableListOf<String>()

    private val engine =
        MockEngine { request ->
            if (request.url.host == "texas.test") {
                if (texasStatus == HttpStatusCode.OK) {
                    respondJson("""{"access_token":"token","expires_in":3599,"token_type":"Bearer"}""")
                } else {
                    respond("", texasStatus)
                }
            } else {
                val body = request.body.toByteArray().decodeToString()
                pdlBodies += body
                if (body.contains("hentPersonBolkQuery")) bolk(request) else pdl(request)
            }
        }

    val client: PdlClient =
        createHttpClient(engine, timeoutMillis).let { PdlClient(it, testPdlConfig, TexasTokenClient(it, testPdlConfig.tokenEndpoint)) }
}

/** For tests that never reach PDL. Fails loudly if PDL is called anyway. */
fun unusedPdlClient(): PdlClient = FakePdl { error("PDL should not be called in this test") }.client

fun MockRequestHandleScope.respondJson(
    json: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(json, status, headersOf(HttpHeaders.ContentType, "application/json"))

fun pdlResource(name: String): String =
    requireNotNull(FakePdl::class.java.getResource("/pdl/$name")) { "Missing test resource $name" }.readText()
