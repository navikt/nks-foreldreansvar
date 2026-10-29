package no.nav.nks.foreldreansvar

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VerifiserRouteTest {
    private val issuerId = "azuread"
    private val audience = "nks-foreldreansvar-client-id"
    private val saasProxyClientId = "saas-proxy-client-id"
    private val mockOAuth2Server = MockOAuth2Server()
    private val mapper = jacksonObjectMapper()

    private val forelderIdent = "01020312345"

    // ansvarssubjekt in pdl/foreldreansvar-ok.json
    private val barnIdent = "10101012345"
    private val annetBarnIdent = "20202012345"

    private val path = "/api/foreldreansvar/verifiser"

    @BeforeAll
    fun start() = mockOAuth2Server.start()

    @AfterAll
    fun stop() = mockOAuth2Server.shutdown()

    @Test
    fun `child in the parent's foreldreansvar without adressebeskyttelse can be represented`() {
        val pdl = FakePdl(bolk = { respondJson(barnBolk("UGRADERT")) }) { respondJson(pdlResource("foreldreansvar-ok.json")) }

        val response = post(pdl, body(forelderIdent, barnIdent))

        assertKanRepresentere(true, response)
        val variables = mapper.readTree(pdl.pdlBodies[0])["variables"]
        assertEquals(forelderIdent, variables["ident"].asText())
        assertFalse(variables["historikk"].asBoolean())
        // The child is looked up on its own to get its adressebeskyttelse.
        val bolkVariables = mapper.readTree(pdl.pdlBodies[1])["variables"]
        assertEquals(listOf(barnIdent), bolkVariables["identer"].map { it.asText() })
    }

    @Test
    fun `child without any adressebeskyttelse entry can be represented`() {
        val pdl =
            FakePdl(bolk = {
                respondJson("""{"data":{"hentPersonBolk":[{"ident":"$barnIdent","code":"ok","person":{"adressebeskyttelse":[]}}]}}""")
            }) { respondJson(forelderMedAdressebeskyttelse("foreldreansvar-ok.json", "UGRADERT")) }

        assertKanRepresentere(true, post(pdl, body(forelderIdent, barnIdent)))
    }

    @Test
    fun `parent with adressebeskyttelse cannot represent and the child is not looked up`() {
        for (gradering in BESKYTTET) {
            val pdl = FakePdl { respondJson(forelderMedAdressebeskyttelse("foreldreansvar-ok.json", gradering)) }

            assertKanRepresentere(false, post(pdl, body(forelderIdent, barnIdent)))
            assertEquals(1, pdl.pdlBodies.size)
        }
    }

    @Test
    fun `child with adressebeskyttelse cannot be represented`() {
        for (gradering in BESKYTTET) {
            val pdl = FakePdl(bolk = { respondJson(barnBolk(gradering)) }) { respondJson(pdlResource("foreldreansvar-ok.json")) }

            assertKanRepresentere(false, post(pdl, body(forelderIdent, barnIdent)))
        }
    }

    @Test
    fun `dead child cannot be represented`() {
        val pdl =
            FakePdl(bolk = {
                respondJson(
                    """{"data":{"hentPersonBolk":[{"ident":"$barnIdent","code":"ok","person":{"doedsfall":[{"doedsdato":"2024-01-01"}]}}]}}""",
                )
            }) { respondJson(pdlResource("foreldreansvar-ok.json")) }

        assertKanRepresentere(false, post(pdl, body(forelderIdent, barnIdent)))
    }

    @Test
    fun `child not found in PDL cannot be represented`() {
        val pdl =
            FakePdl(bolk = {
                respondJson("""{"data":{"hentPersonBolk":[{"ident":"$barnIdent","code":"not_found","person":null}]}}""")
            }) { respondJson(pdlResource("foreldreansvar-ok.json")) }

        assertKanRepresentere(false, post(pdl, body(forelderIdent, barnIdent)))
    }

    @Test
    fun `child lookup failing returns 502`() {
        val pdl =
            FakePdl(bolk = { respond("", HttpStatusCode.InternalServerError) }) { respondJson(pdlResource("foreldreansvar-ok.json")) }

        assertProblem(post(pdl, body(forelderIdent, barnIdent)), HttpStatusCode.BadGateway)
    }

    @Test
    fun `child not in the parent's foreldreansvar cannot be represented`() {
        val pdl = FakePdl { respondJson(pdlResource("foreldreansvar-ok.json")) }

        val response = post(pdl, body(forelderIdent, annetBarnIdent))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(mapper.readTree("""{"kanRepresentere":false}"""), mapper.readTree(response.body))
    }

    @Test
    fun `parent without foreldreansvar cannot represent`() {
        val pdl = FakePdl { respondJson("""{"data":{"hentPerson":{"foreldreansvar":[]}}}""") }

        val response = post(pdl, body(forelderIdent, barnIdent))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(mapper.readTree("""{"kanRepresentere":false}"""), mapper.readTree(response.body))
    }

    @Test
    fun `parent not in PDL returns 404`() = assertPdlError(FakePdl { respondJson(pdlResource("not-found.json")) }, HttpStatusCode.NotFound)

    @Test
    fun `PDL bad_request returns 400`() =
        assertPdlError(FakePdl { respondJson(pdlResource("bad-request.json")) }, HttpStatusCode.BadRequest)

    @Test
    fun `PDL denying our access returns 502`() =
        assertPdlError(FakePdl { respondJson(pdlResource("unauthorized.json")) }, HttpStatusCode.BadGateway)

    @Test
    fun `PDL HTTP 500 returns 502`() =
        assertPdlError(FakePdl { respond("", HttpStatusCode.InternalServerError) }, HttpStatusCode.BadGateway)

    @Test
    fun `Texas failure returns 500`() =
        assertPdlError(
            FakePdl(texasStatus = HttpStatusCode.InternalServerError) { error("PDL should not be called") },
            HttpStatusCode.InternalServerError,
        )

    @Test
    fun `PDL timeout returns 504`() =
        assertPdlError(
            FakePdl(timeoutMillis = 100) {
                delay(1_000)
                respondJson(pdlResource("foreldreansvar-ok.json"))
            },
            HttpStatusCode.GatewayTimeout,
        )

    @Test
    fun `missing fields return 400`() {
        assertBadRequest("""{}""")
        assertBadRequest("""{"barnIdent":"$barnIdent"}""")
        assertBadRequest("""{"forelderIdent":"$forelderIdent"}""")
        assertBadRequest("""{"forelderIdent":null,"barnIdent":"$barnIdent"}""")
    }

    @Test
    fun `blank fields return 400`() {
        assertBadRequest(body("   ", barnIdent))
        assertBadRequest(body(forelderIdent, "   "))
    }

    @Test
    fun `idents that are not 11 digits return 400`() {
        assertBadRequest(body("1234567891", barnIdent))
        assertBadRequest(body(forelderIdent, "123456789101"))
        assertBadRequest(body(forelderIdent, "1010101234a"))
    }

    @Test
    fun `invalid JSON returns 400 without echoing the body`() =
        assertBadRequest("""{"forelderIdent":"$forelderIdent","barnIdent":"$barnIdent"""")

    @Test
    fun `missing token returns 401`() =
        testApplication {
            application { module(testAzureConfig(), unusedPdlClient()) }

            val response =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    setBody(body(forelderIdent, barnIdent))
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    private fun barnBolk(gradering: String) =
        """{"data":{"hentPersonBolk":[{"ident":"$barnIdent","code":"ok","person":{"adressebeskyttelse":[{"gradering":"$gradering"}]}}]}}"""

    private fun assertKanRepresentere(
        expected: Boolean,
        response: Response,
    ) {
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(mapper.readTree("""{"kanRepresentere":$expected}"""), mapper.readTree(response.body))
    }

    private fun body(
        forelder: String,
        barn: String,
    ) = """{"forelderIdent":"$forelder","barnIdent":"$barn"}"""

    private fun assertPdlError(
        pdl: FakePdl,
        expected: HttpStatusCode,
    ) {
        val response = post(pdl, body(forelderIdent, barnIdent))
        assertProblem(response, expected)
    }

    private fun assertBadRequest(body: String) {
        val response = post(FakePdl { error("PDL should not be called") }, body)
        assertProblem(response, HttpStatusCode.BadRequest)
    }

    private fun assertProblem(
        response: Response,
        expected: HttpStatusCode,
    ) {
        assertEquals(expected, response.status)
        assertTrue(response.contentType.startsWith("application/problem+json"), "Content-Type was ${response.contentType}")
        val problem = mapper.readValue<Problem>(response.body)
        assertEquals(expected.value, problem.status)
        assertTrue(problem.detail.isNotBlank())
        assertFalse(response.body.contains(forelderIdent), "Error body must not contain forelderIdent")
        assertFalse(response.body.contains(barnIdent), "Error body must not contain barnIdent")
    }

    private data class Response(
        val status: HttpStatusCode,
        val contentType: String,
        val body: String,
    )

    private fun post(
        pdl: FakePdl,
        body: String,
    ): Response {
        lateinit var result: Response
        testApplication {
            application { module(testAzureConfig(), pdl.client) }

            val response: HttpResponse =
                client.post(path) {
                    bearerAuth(token())
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            result = Response(response.status, response.headers["Content-Type"].orEmpty(), response.bodyAsText())
        }
        return result
    }

    private fun token(): String =
        mockOAuth2Server
            .issueToken(
                issuerId = issuerId,
                clientId = saasProxyClientId,
                tokenCallback =
                    DefaultOAuth2TokenCallback(
                        issuerId = issuerId,
                        subject = "subject",
                        audience = listOf(audience),
                        claims = mapOf("idtyp" to "app", "roles" to listOf("access_as_application"), "azp" to saasProxyClientId),
                    ),
            ).serialize()

    private fun testAzureConfig() =
        AzureConfig(
            clientId = audience,
            issuer = mockOAuth2Server.issuerUrl(issuerId).toString(),
            jwksUri = mockOAuth2Server.jwksUrl(issuerId).toString(),
            preAuthorizedClientIds = setOf(saasProxyClientId),
        )
}
