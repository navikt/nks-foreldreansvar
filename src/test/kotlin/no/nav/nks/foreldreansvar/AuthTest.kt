package no.nav.nks.foreldreansvar

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthTest {
    private val issuerId = "azuread"
    private val audience = "nks-foreldreansvar-client-id"
    private val saasProxyClientId = "saas-proxy-client-id"
    private val mockOAuth2Server = MockOAuth2Server()

    private val validClaims =
        mapOf(
            "idtyp" to "app",
            "roles" to listOf("access_as_application"),
            "azp" to saasProxyClientId,
        )

    @BeforeAll
    fun start() = mockOAuth2Server.start()

    @AfterAll
    fun stop() = mockOAuth2Server.shutdown()

    @Test
    fun `missing token returns 401`() = assertAuth(token = null, expected = HttpStatusCode.Unauthorized)

    @Test
    fun `valid M2M token from pre-authorized client passes auth`() =
        // An empty body fails validation (400) after auth, so PDL is never called.
        assertAuth(token = token(), expected = HttpStatusCode.BadRequest)

    @Test
    fun `wrong audience returns 401`() = assertAuth(token = token(audience = "some-other-app"), expected = HttpStatusCode.Unauthorized)

    @Test
    fun `wrong issuer returns 401`() =
        assertAuth(
            token = token(),
            expected = HttpStatusCode.Unauthorized,
            config = testAzureConfig().copy(issuer = mockOAuth2Server.issuerUrl("another-issuer").toString()),
        )

    @Test
    fun `user token (not idtyp=app) returns 401`() =
        assertAuth(token = token(claims = validClaims - "idtyp"), expected = HttpStatusCode.Unauthorized)

    @Test
    fun `token without access_as_application role returns 401`() =
        assertAuth(token = token(claims = validClaims - "roles"), expected = HttpStatusCode.Unauthorized)

    @Test
    fun `client that is not pre-authorized returns 401`() =
        assertAuth(
            token = token(claims = validClaims + ("azp" to "unknown-client")),
            expected = HttpStatusCode.Unauthorized,
        )

    @Test
    fun `invalid token returns 401`() = assertAuth(token = "not-a-jwt", expected = HttpStatusCode.Unauthorized)

    @Test
    fun `internal endpoints do not require a token`() =
        testApplication {
            application { module(testAzureConfig(), unusedPdlClient()) }

            assertEquals(HttpStatusCode.OK, client.get("/internal/isalive").status)
            assertEquals(HttpStatusCode.OK, client.get("/internal/isready").status)
        }

    private fun assertAuth(
        token: String?,
        expected: HttpStatusCode,
        config: AzureConfig = testAzureConfig(),
    ) = testApplication {
        application { module(config, unusedPdlClient()) }

        val response: HttpResponse =
            client.post("/api/foreldreansvar/barn") {
                token?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

        assertEquals(expected, response.status)
    }

    private fun token(
        audience: String = this.audience,
        claims: Map<String, Any> = validClaims,
    ): String =
        mockOAuth2Server
            .issueToken(
                issuerId = issuerId,
                clientId = claims["azp"]?.toString() ?: saasProxyClientId,
                tokenCallback =
                    DefaultOAuth2TokenCallback(
                        issuerId = issuerId,
                        subject = "subject",
                        audience = listOf(audience),
                        claims = claims,
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
