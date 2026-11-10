package no.nav.nks.foreldreansvar

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import no.nav.nks.foreldreansvar.pdl.Barn
import no.nav.nks.foreldreansvar.pdl.ForelderBarnRelasjon
import no.nav.nks.foreldreansvar.pdl.Foreldreansvar
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
import java.time.ZoneId

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnsvarligeRouteTest {
    private val issuerId = "azuread"
    private val audience = "nks-foreldreansvar-client-id"
    private val saasProxyClientId = "saas-proxy-client-id"
    private val mockOAuth2Server = MockOAuth2Server()
    private val mapper = jacksonObjectMapper()

    private val path = "/api/foreldreansvar/ansvarlige"
    private val clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneId.of("Europe/Oslo"))

    private val barnIdent = "10101012345"
    private val mor = "01020312345"
    private val far = "05060712345"
    private val medmor = "07080912345"
    private val barnetsBarn = "20202012345"

    @BeforeAll
    fun start() = mockOAuth2Server.start()

    @AfterAll
    fun stop() = mockOAuth2Server.shutdown()

    // --- ansvarligeKandidater (pure rules) ---

    private fun foreldreansvar(
        ansvar: String?,
        ansvarlig: String? = null,
        ansvarssubjekt: String? = barnIdent,
    ) = Foreldreansvar(ansvar, ansvarlig, ansvarssubjekt, null)

    private val foreldre =
        listOf(
            ForelderBarnRelasjon(mor, "MOR"),
            ForelderBarnRelasjon(far, "FAR"),
            ForelderBarnRelasjon(barnetsBarn, "BARN"),
        )

    @Test
    fun `uses ansvarlig when it is set`() {
        val barn = Barn(foreldreansvar = listOf(foreldreansvar("felles", mor), foreldreansvar("felles", far)))
        assertEquals(listOf(AnsvarligDto(mor, "felles"), AnsvarligDto(far, "felles")), barn.ansvarligeKandidater(barnIdent))
    }

    @Test
    fun `felles without ansvarlig resolves to all parents but never BARN relations`() {
        val barn = Barn(foreldreansvar = listOf(foreldreansvar("felles")), forelderBarnRelasjon = foreldre)
        assertEquals(listOf(AnsvarligDto(mor, "felles"), AnsvarligDto(far, "felles")), barn.ansvarligeKandidater(barnIdent))
    }

    @Test
    fun `mor, far and medmor without ansvarlig resolve to the matching parent`() {
        val relasjoner = foreldre + ForelderBarnRelasjon(medmor, "MEDMOR")
        assertEquals(
            listOf(AnsvarligDto(mor, "mor")),
            Barn(foreldreansvar = listOf(foreldreansvar("mor")), forelderBarnRelasjon = relasjoner).ansvarligeKandidater(barnIdent),
        )
        assertEquals(
            listOf(AnsvarligDto(far, "far")),
            Barn(foreldreansvar = listOf(foreldreansvar("far")), forelderBarnRelasjon = relasjoner).ansvarligeKandidater(barnIdent),
        )
        assertEquals(
            listOf(AnsvarligDto(medmor, "medmor")),
            Barn(foreldreansvar = listOf(foreldreansvar("medmor")), forelderBarnRelasjon = relasjoner).ansvarligeKandidater(barnIdent),
        )
    }

    @Test
    fun `entries without an ident to share with are skipped`() {
        val barn =
            Barn(
                foreldreansvar = listOf(foreldreansvar("andre"), foreldreansvar("ukjent"), foreldreansvar(null), foreldreansvar("mor")),
                // No MOR relation, so "mor" cannot be resolved either.
                forelderBarnRelasjon = listOf(ForelderBarnRelasjon(far, "FAR"), ForelderBarnRelasjon(null, "MOR")),
            )
        assertEquals(emptyList<AnsvarligDto>(), barn.ansvarligeKandidater(barnIdent))
    }

    @Test
    fun `andre with ansvarlig is kept`() {
        val barn = Barn(foreldreansvar = listOf(foreldreansvar("andre", medmor)))
        assertEquals(listOf(AnsvarligDto(medmor, "andre")), barn.ansvarligeKandidater(barnIdent))
    }

    @Test
    fun `each ident once, first ansvar wins, and entries for other children are ignored`() {
        val barn =
            Barn(
                foreldreansvar =
                    listOf(
                        foreldreansvar("felles", mor),
                        foreldreansvar("felles"),
                        foreldreansvar("andre", medmor, ansvarssubjekt = barnetsBarn),
                        foreldreansvar("andre", barnIdent),
                    ),
                forelderBarnRelasjon = foreldre,
            )
        assertEquals(listOf(AnsvarligDto(mor, "felles"), AnsvarligDto(far, "felles")), barn.ansvarligeKandidater(barnIdent))
    }

    // --- POST /api/foreldreansvar/ansvarlige ---

    private fun barnJson(
        foreldreansvar: String,
        gradering: String? = null,
        foedselsdato: String = """[{"foedselsdato":"2015-03-01","foedselsaar":2015}]""",
    ): String {
        val adressebeskyttelse = gradering?.let { """[{"gradering":"$it"}]""" } ?: "[]"
        return """{"data":{"hentBarn":{
            "adressebeskyttelse":$adressebeskyttelse,
            "foedselsdato":$foedselsdato,
            "foreldreansvar":$foreldreansvar,
            "forelderBarnRelasjon":[
              {"relatertPersonsIdent":"$mor","relatertPersonsRolle":"MOR"},
              {"relatertPersonsIdent":"$far","relatertPersonsRolle":"FAR"},
              {"relatertPersonsIdent":"$barnetsBarn","relatertPersonsRolle":"BARN"}
            ]}}}"""
    }

    private val felles = """[{"ansvar":"felles","ansvarlig":null,"ansvarssubjekt":"$barnIdent"}]"""

    private fun bolk(vararg personer: Pair<String, String?>): String =
        personer.joinToString(",", """{"data":{"hentPersonBolk":[""", "]}}") { (ident, gradering) ->
            if (gradering == null) {
                """{"ident":"$ident","code":"not_found","person":null}"""
            } else {
                """{"ident":"$ident","code":"ok","person":{"adressebeskyttelse":[{"gradering":"$gradering"}]}}"""
            }
        }

    @Test
    fun `returns the child's ansvarlige with hentetTidspunkt`() {
        val pdl = FakePdl(bolk = { respondJson(bolk(mor to "UGRADERT", far to "UGRADERT")) }) { respondJson(barnJson(felles)) }

        val response = post(pdl, """{"barnIdent":"$barnIdent"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            mapper.readTree(
                """{"barnIdent":"$barnIdent","ansvarlige":[{"ident":"$mor","ansvar":"felles"},{"ident":"$far","ansvar":"felles"}],
                   "hentetTidspunkt":"2026-10-29T10:00:00Z"}""",
            ),
            mapper.readTree(response.body),
        )
        assertEquals(2, pdl.pdlBodies.size)
        assertTrue(pdl.pdlBodies[0].contains("barnAnsvarligeQuery"))
        assertEquals(barnIdent, mapper.readTree(pdl.pdlBodies[0])["variables"]["ident"].asText())
        assertEquals(listOf(mor, far), mapper.readTree(pdl.pdlBodies[1])["variables"]["identer"].map { it.asText() })
    }

    @Test
    fun `protected child gets an empty list without looking up the parents`() {
        for (gradering in BESKYTTET + "UKJENT_VERDI") {
            val pdl = FakePdl { respondJson(barnJson(felles, gradering)) }

            val response = post(pdl, """{"barnIdent":"$barnIdent"}""")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(0, mapper.readTree(response.body)["ansvarlige"].size(), gradering)
            assertEquals(1, pdl.pdlBodies.size)
        }
    }

    @Test
    fun `myndig child or child with unknown age gets an empty list without looking up the parents`() {
        // The clock is 2026-10-29: born 2008-10-29 turns 18 today, foedselsaar 2008 is ambiguous.
        for (foedselsdato in listOf(
            """[{"foedselsdato":"2008-10-29"}]""",
            """[{"foedselsdato":null,"foedselsaar":2008}]""",
            """[{"foedselsdato":null,"foedselsaar":2007}]""",
            "[]",
        )) {
            val pdl = FakePdl { respondJson(barnJson(felles, foedselsdato = foedselsdato)) }

            val response = post(pdl, """{"barnIdent":"$barnIdent"}""")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(0, mapper.readTree(response.body)["ansvarlige"].size(), foedselsdato)
            assertEquals(1, pdl.pdlBodies.size)
        }
    }

    @Test
    fun `child the day before turning 18 keeps its ansvarlige`() {
        val pdl =
            FakePdl(bolk = { respondJson(bolk(mor to "UGRADERT", far to "UGRADERT")) }) {
                respondJson(barnJson(felles, foedselsdato = """[{"foedselsdato":"2008-10-30"}]"""))
            }

        val response = post(pdl, """{"barnIdent":"$barnIdent"}""")

        assertEquals(listOf(mor, far), mapper.readTree(response.body)["ansvarlige"].map { it["ident"].asText() })
    }

    @Test
    fun `protected parents and parents PDL does not return are left out`() {
        val pdl =
            FakePdl(bolk = { respondJson(bolk(mor to "FORTROLIG", far to "UGRADERT", medmor to null)) }) {
                respondJson(
                    barnJson(
                        """[{"ansvar":"felles","ansvarlig":"$mor","ansvarssubjekt":"$barnIdent"},
                            {"ansvar":"felles","ansvarlig":"$far","ansvarssubjekt":"$barnIdent"},
                            {"ansvar":"andre","ansvarlig":"$medmor","ansvarssubjekt":"$barnIdent"}]""",
                    ),
                )
            }

        val response = post(pdl, """{"barnIdent":"$barnIdent"}""")

        assertEquals(listOf(far), mapper.readTree(response.body)["ansvarlige"].map { it["ident"].asText() })
    }

    @Test
    fun `child without foreldreansvar gets an empty list without calling bolk`() {
        val pdl = FakePdl { respondJson(barnJson("[]")) }

        val response = post(pdl, """{"barnIdent":"$barnIdent"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, mapper.readTree(response.body)["ansvarlige"].size())
        assertEquals(1, pdl.pdlBodies.size)
    }

    @Test
    fun `child not in PDL returns 404`() {
        assertProblem(
            post(FakePdl { respondJson(pdlResource("not-found.json")) }, """{"barnIdent":"$barnIdent"}"""),
            HttpStatusCode.NotFound,
        )
        assertProblem(
            post(FakePdl { respondJson("""{"data":{"hentBarn":null}}""") }, """{"barnIdent":"$barnIdent"}"""),
            HttpStatusCode.NotFound,
        )
    }

    @Test
    fun `invalid barnIdent returns 400`() {
        for (body in listOf("""{}""", """{"barnIdent":null}""", """{"barnIdent":"123"}""", """{"barnIdent":"$barnIdent"""")) {
            assertProblem(post(FakePdl { error("PDL should not be called") }, body), HttpStatusCode.BadRequest)
        }
    }

    @Test
    fun `missing token returns 401`() =
        testApplication {
            application { module(testAzureConfig(), unusedPdlClient()) }

            val response =
                client.post(path) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"barnIdent":"$barnIdent"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    private fun assertProblem(
        response: Response,
        expected: HttpStatusCode,
    ) {
        assertEquals(expected, response.status)
        assertTrue(response.contentType.startsWith("application/problem+json"), "Content-Type was ${response.contentType}")
        assertEquals(expected.value, mapper.readValue<Problem>(response.body).status)
        assertFalse(response.body.contains(barnIdent), "Error body must not contain the ident")
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
            application { module(testAzureConfig(), pdl.client, clock) }

            val response =
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
