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
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
            behandlingsnummer = "B118",
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
        meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    ): PdlClient {
        val httpClient = createHttpClient(servers.engine, timeoutMillis)
        return PdlClient(httpClient, config, TexasTokenClient(httpClient, config.tokenEndpoint), meterRegistry)
    }

    @Test
    fun `times each call by operation and outcome`() {
        val meterRegistry = SimpleMeterRegistry()
        val servers =
            FakeServers(
                { respondJson(resource("foreldreansvar-ok.json")) },
                { respondJson(resource("not-found.json")) },
                { respondJson(resource("unauthorized.json")) },
            )
        val client = client(servers, meterRegistry = meterRegistry)

        runBlocking {
            client.hentForelder(ident)
            assertThrows<PdlException.NotFound> { client.hentForelder(ident) }
            assertThrows<PdlException.Unauthorized> { client.hentBarn(ident) }
        }

        fun count(
            operation: String,
            outcome: String,
        ) = meterRegistry
            .find("pdl.requests")
            .tags("operation", operation, "outcome", outcome)
            .timer()
            ?.count()
        assertEquals(1L, count("hentForelder", "ok"))
        assertEquals(1L, count("hentForelder", "not_found"))
        assertEquals(1L, count("hentBarn", "unauthorized"))
        // No idents in tag values.
        assertTrue(meterRegistry.meters.flatMap { it.id.tags }.none { ident in it.value })
    }

    @Test
    fun `returns foreldreansvar and sends token, behandlingsnummer and variables`() {
        val servers = FakeServers({ respondJson(resource("foreldreansvar-ok.json")) })

        val result = runBlocking { client(servers).hentForelder(ident, historikk = true) }

        assertEquals(2, result.foreldreansvar.size)
        val foreldreansvar = result.foreldreansvar
        assertEquals("felles", foreldreansvar[0].ansvar)
        assertEquals("01020312345", foreldreansvar[0].ansvarlig)
        assertNull(foreldreansvar[0].ansvarligUtenIdentifikator)
        val utenIdent = foreldreansvar[1].ansvarligUtenIdentifikator!!
        assertNull(foreldreansvar[1].ansvarlig)
        assertEquals("Kari", utenIdent.navn!!.fornavn)
        assertEquals("SWE", utenIdent.statsborgerskap)

        val pdlRequest = servers.pdlRequests.single()
        assertEquals("Bearer token-1", pdlRequest.headers[HttpHeaders.Authorization])
        assertEquals("B118", pdlRequest.headers["Behandlingsnummer"])
        val body = servers.pdlBodies.single()
        assertTrue(body.contains(""""variables":{"ident":"$ident","historikk":true}"""), body)
        assertTrue(body.contains("foreldreansvarQuery"))
    }

    @Test
    fun `reads the parent's adressebeskyttelse and asks only for current values`() {
        val servers =
            FakeServers({
                respondJson("""{"data":{"hentPerson":{"adressebeskyttelse":[{"gradering":"FORTROLIG"}],"foreldreansvar":[]}}}""")
            })

        val result = runBlocking { client(servers).hentForelder(ident) }

        assertEquals(listOf(Adressebeskyttelse("FORTROLIG")), result.adressebeskyttelse)
        assertTrue(servers.pdlBodies.single().contains("adressebeskyttelse(historikk: false)"))
    }

    @Test
    fun `missing adressebeskyttelse gives an empty list`() {
        val servers = FakeServers({ respondJson(resource("foreldreansvar-ok.json")) })

        val result = runBlocking { client(servers).hentForelder(ident) }

        assertTrue(result.adressebeskyttelse.isEmpty())
    }

    @Test
    fun `asks Texas for an Entra ID token for the PDL target`() {
        val servers = FakeServers({ respondJson(resource("foreldreansvar-ok.json")) })

        runBlocking { client(servers).hentForelder(ident) }

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
            client.hentForelder(ident)
            client.hentForelder(ident)
        }

        assertEquals(2, servers.texasRequests.size)
        assertFalse(servers.texasRequests.any { it.containsKey("skip_cache") })
    }

    @Test
    fun `not_found error gives NotFound`() {
        val servers = FakeServers({ respondJson(resource("not-found.json")) })

        assertThrows<PdlException.NotFound> { runBlocking { client(servers).hentForelder(ident) } }
    }

    @Test
    fun `unauthorized error gives Unauthorized`() {
        val servers = FakeServers({ respondJson(resource("unauthorized.json")) })

        assertThrows<PdlException.Unauthorized> { runBlocking { client(servers).hentForelder(ident) } }
    }

    @Test
    fun `bad_request error gives BadRequest`() {
        val servers = FakeServers({ respondJson(resource("bad-request.json")) })

        assertThrows<PdlException.BadRequest> { runBlocking { client(servers).hentForelder(ident) } }
    }

    @Test
    fun `error messages never contain the ident`() {
        val servers = FakeServers({ respondJson(resource("not-found.json")) })

        val error = assertThrows<PdlException> { runBlocking { client(servers).hentForelder(ident) } }

        assertFalse(error.message!!.contains(ident))
    }

    @Test
    fun `HTTP 401 retries once with a fresh token from Texas`() {
        val servers =
            FakeServers(
                { respond("", HttpStatusCode.Unauthorized) },
                { respondJson(resource("foreldreansvar-ok.json")) },
            )

        val result = runBlocking { client(servers).hentForelder(ident) }

        assertEquals(2, result.foreldreansvar.size)
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

        assertThrows<PdlException.Unauthorized> { runBlocking { client(servers).hentForelder(ident) } }
        assertEquals(2, servers.pdlRequests.size)
    }

    @Test
    fun `HTTP 5xx gives Unexpected`() {
        val servers = FakeServers({ respond("oops", HttpStatusCode.ServiceUnavailable) })

        val error = assertThrows<PdlException.Unexpected> { runBlocking { client(servers).hentForelder(ident) } }

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
            runBlocking { client(servers, timeoutMillis = 100).hentForelder(ident) }
        }
    }

    @Test
    fun `hentPersonBolk returns only ok results and sends token, behandlingsnummer and variables`() {
        val servers = FakeServers({ respondJson(resource("person-bolk-ok.json")) })
        val identer = listOf("10101012345", "20202012345", "30303012345")

        val result = runBlocking { client(servers).hentPersonBolk(identer, historikk = false) }

        assertEquals(listOf("20202012345", "10101012345"), result.map { it.ident })
        val ola = result.single { it.ident == "10101012345" }.person!!
        assertEquals("Ola", ola.navn.single().fornavn)
        assertEquals("2015-03-01", ola.foedselsdato.single().foedselsdato)
        assertEquals(2015, ola.foedselsdato.single().foedselsaar)
        assertEquals(listOf(Adressebeskyttelse("UGRADERT")), ola.adressebeskyttelse)
        assertTrue(ola.doedsfall.isEmpty())
        assertTrue(
            result
                .single { it.ident == "20202012345" }
                .person!!
                .adressebeskyttelse
                .isEmpty(),
        )

        val pdlRequest = servers.pdlRequests.single()
        assertEquals("Bearer token-1", pdlRequest.headers[HttpHeaders.Authorization])
        assertEquals("B118", pdlRequest.headers["Behandlingsnummer"])
        val body = servers.pdlBodies.single()
        assertTrue(body.contains(""""variables":{"identer":["10101012345","20202012345","30303012345"],"historikk":false}"""), body)
        assertTrue(body.contains("hentPersonBolkQuery"))
        assertTrue(body.contains("adressebeskyttelse(historikk: false)"))
        assertTrue(body.contains("doedsfall"))
    }

    @Test
    fun `hentPersonBolk with no idents does not call PDL`() {
        val servers = FakeServers()

        val result = runBlocking { client(servers).hentPersonBolk(emptyList()) }

        assertTrue(result.isEmpty())
        assertTrue(servers.pdlRequests.isEmpty())
    }

    @Test
    fun `hentPersonBolk unauthorized error gives Unauthorized without the idents in the message`() {
        val servers = FakeServers({ respondJson(resource("unauthorized.json")) })

        val error =
            assertThrows<PdlException.Unauthorized> { runBlocking { client(servers).hentPersonBolk(listOf(ident)) } }

        assertFalse(error.message!!.contains(ident))
    }

    @Test
    fun `Texas failure gives TokenUnavailable`() {
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val httpClient = createHttpClient(engine)
        val client = PdlClient(httpClient, config, TexasTokenClient(httpClient, config.tokenEndpoint))

        assertThrows<PdlException.TokenUnavailable> { runBlocking { client.hentForelder(ident) } }
    }

    private companion object {
        fun MockRequestHandleScope.respondJson(json: String) =
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

        fun resource(name: String): String =
            requireNotNull(PdlClientTest::class.java.getResource("/pdl/$name")) { "Missing test resource $name" }.readText()
    }
}
