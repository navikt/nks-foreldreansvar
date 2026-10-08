package no.nav.nks.foreldreansvar

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApplicationTest {
    @Test
    fun `isalive returns OK`() =
        testApplication {
            application { module() }

            val response = client.get("/internal/isalive")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ALIVE", response.bodyAsText())
        }

    @Test
    fun `isready returns OK`() =
        testApplication {
            application { module() }

            val response = client.get("/internal/isready")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("READY", response.bodyAsText())
        }

    @Test
    fun `unknown paths return not found`() =
        testApplication {
            application { module() }

            assertEquals(HttpStatusCode.NotFound, client.get("/").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/healthz").status)
        }
}
