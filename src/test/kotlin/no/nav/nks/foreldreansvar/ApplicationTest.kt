package no.nav.nks.foreldreansvar

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApplicationTest {
    // JWKS is only fetched when a token is validated, so dummy values are enough here.
    private val dummyAzureConfig =
        AzureConfig(
            clientId = "client-id",
            issuer = "http://localhost/issuer",
            jwksUri = "http://localhost/jwks",
            preAuthorizedClientIds = emptySet(),
        )

    @Test
    fun `isalive returns OK`() =
        testApplication {
            application { module(dummyAzureConfig, unusedPdlClient()) }

            val response = client.get("/internal/isalive")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ALIVE", response.bodyAsText())
        }

    @Test
    fun `isready returns OK`() =
        testApplication {
            application { module(dummyAzureConfig, unusedPdlClient()) }

            val response = client.get("/internal/isready")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("READY", response.bodyAsText())
        }

    @Test
    fun `unknown paths return not found`() =
        testApplication {
            application { module(dummyAzureConfig, unusedPdlClient()) }

            assertEquals(HttpStatusCode.NotFound, client.get("/").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/healthz").status)
        }
}
