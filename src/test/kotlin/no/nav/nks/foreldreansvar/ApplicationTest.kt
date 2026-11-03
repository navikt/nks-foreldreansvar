package no.nav.nks.foreldreansvar

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `isalive returns 503 when a background check fails`() =
        testApplication {
            application { module(dummyAzureConfig, unusedPdlClient(), isAlive = { false }) }

            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/internal/isalive").status)
            // Readiness only reflects the HTTP API.
            assertEquals(HttpStatusCode.OK, client.get("/internal/isready").status)
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
    fun `metrics are served without a token in Prometheus format`() =
        testApplication {
            application { module(dummyAzureConfig, unusedPdlClient()) }
            client.get("/internal/isready")

            val response = client.get("/internal/metrics")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers["Content-Type"]!!.startsWith("text/plain"))
            val body = response.bodyAsText()
            assertTrue("jvm_memory_used_bytes" in body)
            assertTrue("ktor_http_server_requests_seconds_count" in body)
        }

    @Test
    fun `unknown paths return not found`() =
        testApplication {
            application { module(dummyAzureConfig, unusedPdlClient()) }

            assertEquals(HttpStatusCode.NotFound, client.get("/").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/healthz").status)
        }
}
