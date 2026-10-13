package no.nav.nks.foreldreansvar.pdl

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PdlClientTest {
    private val config =
        PdlConfig(
            url = "http://pdl.test/graphql",
            target = "dev-fss:pdl:pdl-api",
            behandlingsnummer = "B530",
            tokenEndpoint = "http://texas.test/api/v1/token",
        )

    private val ident = "12345678910"

    /** Records requests and answers Texas with a numbered token; PDL answers come from [pdlResponses] in order. */
    private class FakeServers(
        vararg pdlResponses: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val texasRequests = mutableListOf<Map<String, String>>()
        val pdlRequests = mutableListOf<HttpRequestData>()
        val pdlBodies = mutableListOf<String>()
        private val remaining = pdlResponses.toMutableList()

        val engine =
            MockEngine { request ->
                if (request.url.host == "texas.test") {
                    val form = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    texasRequests += form.parseUrlEncodedParameters().entries().associate { it.key to it.value.single() }
                    respondJson("""{"access_token":"token-${texasRequests.size}","expires_in":3599,"token_type":"Bearer"}""")
                } else {
                    pdlRequests += request
                    pdlBodies += request.body.toByteArray().decodeToString()
                    remaining.removeFirst()(request)
                }
            }
    }

    private fun client(
        servers: FakeServers,
        timeoutMillis: Long = 10_000,
    ): PdlClient {
        val httpClient = createHttpClient(servers.engine, timeoutMillis)
        return PdlClient(httpClient, config, TexasTokenClient(httpClient, config.tokenEndpoint))
    }

    @Test
    fun `returns foreldreansvar and sends token, behandlingsnummer and variables`() {
        val servers = FakeServers({ respondJson(resource("foreldreansvar-ok.json")) })

        val result = runBlocking { client(servers).hentForeldreansvar(ident, historikk = true) }

        assertEquals(2, result.size)
        assertEquals("felles", result[0].ansvar)
        assertEquals("01020312345", result[0].ansvarlig)
        assertNull(result[0].ansvarligUtenIdentifikator)
        val utenIdent = result[1].ansvarligUtenIdentifikator!!
        assertNull(result[1].ansvarlig)
        assertEquals("Kari", utenIdent.navn!!.fornavn)
        assertEquals("SWE", utenIdent.statsborgerskap)

        val pdlRequest = servers.pdlRequests.single()
        assertEquals("Bearer token-1", pdlRequest.headers[HttpHeaders.Authorization])
        assertEquals("B530", pdlRequest.headers["Behandlingsnummer"])
        val body = servers.pdlBodies.single()
        assertTrue(body.contains(""""variables":{"ident":"$ident","historikk":true}"""), body)
        assertTrue(body.contains("foreldreansvarQuery"))
    }

    @Test
    fun `asks Texas for an Entra ID token for the PDL target`() {
        val servers = FakeServers({ respondJson(resource("foreldreansvar-ok.json")) })

        runBlocking { client(servers).hentForeldreansvar(ident) }

        assertEquals(
            mapOf("identity_provider" to "entra_id", "target" to "dev-fss:pdl:pdl-api"),
            servers.texasRequests.single(),
        )
    }

    @Test
    fun `asks Texas for a token on every call and relies on Texas for caching`() {
        val servers =
            FakeServers(
                { respondJson(resource("foreldreansvar-ok.json")) },
                { respondJson(resource("foreldreansvar-ok.json")) },
            )
        val client = client(servers)

        runBlocking {
            client.hentForeldreansvar(ident)
            client.hentForeldreansvar(ident)
        }

        assertEquals(2, servers.texasRequests.size)
        assertFalse(servers.texasRequests.any { it.containsKey("skip_cache") })
    }

    @Test
    fun `not_found error gives NotFound`() {
        val servers = FakeServers({ respondJson(resource("not-found.json")) })

        assertThrows<PdlException.NotFound> { runBlocking { client(servers).hentForeldreansvar(ident) } }
    }

    @Test
    fun `unauthorized error gives Unauthorized`() {
        val servers = FakeServers({ respondJson(resource("unauthorized.json")) })

        assertThrows<PdlException.Unauthorized> { runBlocking { client(servers).hentForeldreansvar(ident) } }
    }

    @Test
    fun `bad_request error gives BadRequest`() {
        val servers = FakeServers({ respondJson(resource("bad-request.json")) })

        assertThrows<PdlException.BadRequest> { runBlocking { client(servers).hentForeldreansvar(ident) } }
    }

    @Test
    fun `error messages never contain the ident`() {
        val servers = FakeServers({ respondJson(resource("not-found.json")) })

        val error = assertThrows<PdlException> { runBlocking { client(servers).hentForeldreansvar(ident) } }

        assertFalse(error.message!!.contains(ident))
    }

    @Test
    fun `HTTP 401 retries once with a fresh token from Texas`() {
        val servers =
            FakeServers(
                { respond("", HttpStatusCode.Unauthorized) },
                { respondJson(resource("foreldreansvar-ok.json")) },
            )

        val result = runBlocking { client(servers).hentForeldreansvar(ident) }

        assertEquals(2, result.size)
        assertEquals("true", servers.texasRequests[1]["skip_cache"])
        assertEquals("Bearer token-2", servers.pdlRequests[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun `HTTP 401 twice gives Unauthorized`() {
        val servers =
            FakeServers(
                { respond("", HttpStatusCode.Unauthorized) },
                { respond("", HttpStatusCode.Unauthorized) },
            )

        assertThrows<PdlException.Unauthorized> { runBlocking { client(servers).hentForeldreansvar(ident) } }
        assertEquals(2, servers.pdlRequests.size)
    }

    @Test
    fun `HTTP 5xx gives Unexpected`() {
        val servers = FakeServers({ respond("oops", HttpStatusCode.ServiceUnavailable) })

        val error = assertThrows<PdlException.Unexpected> { runBlocking { client(servers).hentForeldreansvar(ident) } }

        assertTrue(error.message!!.contains("503"))
    }

    @Test
    fun `slow PDL gives Timeout`() {
        val servers =
            FakeServers({
                delay(1_000)
                respondJson(resource("foreldreansvar-ok.json"))
            })

        assertThrows<PdlException.Timeout> {
            runBlocking { client(servers, timeoutMillis = 100).hentForeldreansvar(ident) }
        }
    }

    @Test
    fun `Texas failure gives TokenUnavailable`() {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val httpClient = createHttpClient(engine)
        val client = PdlClient(httpClient, config, TexasTokenClient(httpClient, config.tokenEndpoint))

        assertThrows<PdlException.TokenUnavailable> { runBlocking { client.hentForeldreansvar(ident) } }
    }

    private companion object {
        fun MockRequestHandleScope.respondJson(json: String) =
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

        fun resource(name: String): String =
            requireNotNull(PdlClientTest::class.java.getResource("/pdl/$name")) { "Missing test resource $name" }.readText()
    }
}
