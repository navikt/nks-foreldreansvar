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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BarnRouteTest {
    private val issuerId = "azuread"
    private val audience = "nks-foreldreansvar-client-id"
    private val saasProxyClientId = "saas-proxy-client-id"
    private val mockOAuth2Server = MockOAuth2Server()
    private val mapper = jacksonObjectMapper()

    // ansvarlig in pdl/foreldreansvar-forelder.json
    private val ident = "01020312345"

    private val path = "/api/foreldreansvar/barn"

    // 2025-03-01 00:30 in Oslo is still 2025-02-28 in UTC: Ola (born 2015-03-01) is 10 only if Oslo time is used.
    private val clock = Clock.fixed(Instant.parse("2025-02-28T23:30:00Z"), ZoneId.of("Europe/Oslo"))

    @BeforeAll
    fun start() = mockOAuth2Server.start()

    @AfterAll
    fun stop() = mockOAuth2Server.shutdown()

    @Test
    fun `returns the parent's children with name, birth data and age from PDL`() {
        val pdl =
            FakePdl(bolk = { respondJson(pdlResource("person-bolk-ok.json")) }) {
                respondJson(pdlResource("foreldreansvar-forelder.json"))
            }

        val response = postBarn(pdl, """{"ident":"$ident"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        // Order follows foreldreansvar; 30303012345 is not_found in bolk and is left out.
        assertEquals(
            mapper.readTree(
                """
                {"barn":[
                  {"ident":"10101012345","fornavn":"Ola","etternavn":"Nordmann","foedselsdato":"2015-03-01","foedselsaar":2015,"alder":10},
                  {"ident":"20202012345","fornavn":null,"etternavn":null,"foedselsdato":null,"foedselsaar":2018,"alder":null}
                ]}
                """,
            ),
            mapper.readTree(response.body),
        )
        assertEquals(2, pdl.pdlBodies.size)
        val foreldreansvarVariables = mapper.readTree(pdl.pdlBodies[0])["variables"]
        assertEquals(ident, foreldreansvarVariables["ident"].asText())
        assertFalse(foreldreansvarVariables["historikk"].asBoolean())
        // Each child once, without the parent's own ident and without null.
        val bolkVariables = mapper.readTree(pdl.pdlBodies[1])["variables"]
        assertEquals(listOf("10101012345", "20202012345", "30303012345"), bolkVariables["identer"].map { it.asText() })
        assertFalse(bolkVariables["historikk"].asBoolean())
    }

    @Test
    fun `alder counts whole years and is null without a valid foedselsdato`() {
        val today = LocalDate.parse("2025-06-15")
        assertEquals(10, alder("2015-06-15", today))
        assertEquals(9, alder("2015-06-16", today))
        assertEquals(0, alder("2025-06-15", today))
        assertEquals(null, alder(null, today))
        assertEquals(null, alder("2015", today))
        assertEquals(null, alder("2030-01-01", today))
    }

    @Test
    fun `parent without foreldreansvar returns empty list without calling bolk`() {
        val pdl = FakePdl { respondJson("""{"data":{"hentPerson":{"foreldreansvar":[]}}}""") }

        val response = postBarn(pdl, """{"ident":"$ident"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(mapper.readTree("""{"barn":[]}"""), mapper.readTree(response.body))
        assertEquals(1, pdl.pdlBodies.size)
    }

    @Test
    fun `foreldreansvar where the person is only the child returns empty list without calling bolk`() {
        val pdl =
            FakePdl {
                respondJson(
                    """{"data":{"hentPerson":{"foreldreansvar":[
                       {"ansvar":"felles","ansvarlig":"05060712345","ansvarssubjekt":"$ident","ansvarligUtenIdentifikator":null}
                    ]}}}""",
                )
            }

        val response = postBarn(pdl, """{"ident":"$ident"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(mapper.readTree("""{"barn":[]}"""), mapper.readTree(response.body))
    }

    @Test
    fun `parent with adressebeskyttelse gets an empty list without calling bolk`() {
        for (gradering in BESKYTTET) {
            val pdl = FakePdl { respondJson(forelderMedAdressebeskyttelse("foreldreansvar-forelder.json", gradering)) }

            val response = postBarn(pdl, """{"ident":"$ident"}""")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(mapper.readTree("""{"barn":[]}"""), mapper.readTree(response.body), gradering)
            assertEquals(1, pdl.pdlBodies.size)
        }
    }

    @Test
    fun `parent with UGRADERT gets their children`() {
        val pdl =
            FakePdl(bolk = { respondJson(pdlResource("person-bolk-ok.json")) }) {
                respondJson(forelderMedAdressebeskyttelse("foreldreansvar-forelder.json", "UGRADERT"))
            }

        val response = postBarn(pdl, """{"ident":"$ident"}""")

        assertEquals(listOf("10101012345", "20202012345"), mapper.readTree(response.body)["barn"].map { it["ident"].asText() })
    }

    @Test
    fun `children with adressebeskyttelse are left out`() {
        val pdl =
            FakePdl(bolk = {
                respondJson(
                    """{"data":{"hentPersonBolk":[
                       {"ident":"10101012345","code":"ok","person":{"adressebeskyttelse":[{"gradering":"STRENGT_FORTROLIG"}]}},
                       {"ident":"20202012345","code":"ok","person":{"adressebeskyttelse":[{"gradering":"UGRADERT"}]}},
                       {"ident":"30303012345","code":"ok","person":{"adressebeskyttelse":[{"gradering":"FORTROLIG"}]}}
                    ]}}""",
                )
            }) { respondJson(pdlResource("foreldreansvar-forelder.json")) }

        val response = postBarn(pdl, """{"ident":"$ident"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("20202012345"), mapper.readTree(response.body)["barn"].map { it["ident"].asText() })
    }

    @Test
    fun `dead children are left out`() {
        val pdl =
            FakePdl(bolk = {
                respondJson(
                    """{"data":{"hentPersonBolk":[
                       {"ident":"10101012345","code":"ok","person":{"doedsfall":[{"doedsdato":"2024-01-01"}]}},
                       {"ident":"20202012345","code":"ok","person":{"doedsfall":[]}},
                       {"ident":"30303012345","code":"ok","person":{"doedsfall":[{"doedsdato":null}]}}
                    ]}}""",
                )
            }) { respondJson(pdlResource("foreldreansvar-forelder.json")) }

        val response = postBarn(pdl, """{"ident":"$ident"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("20202012345"), mapper.readTree(response.body)["barn"].map { it["ident"].asText() })
    }

    @Test
    fun `no children found in bolk returns empty list`() {
        val pdl =
            FakePdl(bolk = {
                respondJson("""{"data":{"hentPersonBolk":[{"ident":"10101012345","code":"not_found","person":null}]}}""")
            }) { respondJson(pdlResource("foreldreansvar-ok.json")) }

        val response = postBarn(pdl, """{"ident":"$ident"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(mapper.readTree("""{"barn":[]}"""), mapper.readTree(response.body))
    }

    @Test
    fun `bolk denying our access returns 502`() =
        assertPdlError(
            FakePdl(bolk = { respondJson(pdlResource("unauthorized.json")) }) { respondJson(pdlResource("foreldreansvar-ok.json")) },
            HttpStatusCode.BadGateway,
        )

    @Test
    fun `bolk HTTP 500 returns 502`() =
        assertPdlError(
            FakePdl(bolk = { respond("", HttpStatusCode.InternalServerError) }) { respondJson(pdlResource("foreldreansvar-ok.json")) },
            HttpStatusCode.BadGateway,
        )

    @Test
    fun `old path api person is gone`() =
        testApplication {
            application { module(testAzureConfig(), unusedPdlClient()) }

            val response =
                client.post("/api/person") {
                    bearerAuth(token())
                    contentType(ContentType.Application.Json)
                    setBody("""{"ident":"$ident"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `person not in PDL returns 404`() = assertPdlError(FakePdl { respondJson(pdlResource("not-found.json")) }, HttpStatusCode.NotFound)

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
    fun `missing ident returns 400`() = assertBadRequest("""{}""")

    @Test
    fun `null ident returns 400`() = assertBadRequest("""{"ident":null}""")

    @Test
    fun `blank ident returns 400`() = assertBadRequest("""{"ident":"   "}""")

    @Test
    fun `ident that is not 11 digits returns 400`() {
        assertBadRequest("""{"ident":"1234567891"}""")
        assertBadRequest("""{"ident":"123456789101"}""")
        assertBadRequest("""{"ident":"1234567891a"}""")
    }

    @Test
    fun `invalid JSON returns 400 without echoing the body`() = assertBadRequest("""{"ident":"$ident"""")

    @Test
    fun `missing token returns 401`() =
        testApplication {
            application { module(testAzureConfig(), unusedPdlClient()) }

            val response =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"ident":"$ident"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    private fun assertPdlError(
        pdl: FakePdl,
        expected: HttpStatusCode,
    ) {
        val response = postBarn(pdl, """{"ident":"$ident"}""")
        assertProblem(response, expected)
    }

    private fun assertBadRequest(body: String) {
        val response = postBarn(FakePdl { error("PDL should not be called") }, body)
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
        assertFalse(response.body.contains(ident), "Error body must not contain the ident")
    }

    private data class Response(
        val status: HttpStatusCode,
        val contentType: String,
        val body: String,
    )

    private fun postBarn(
        pdl: FakePdl,
        body: String,
    ): Response {
        lateinit var result: Response
        testApplication {
            application { module(testAzureConfig(), pdl.client, clock) }

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
