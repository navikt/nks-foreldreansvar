package no.nav.nks.foreldreansvar

import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.NO_CONTENT
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.SEE_OTHER
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationTest {
    @Test
    fun `visits increment the counter and redirect to the page`() {
        val handler = Application().api()

        fun assertCount(expected: Long) {
            val response = handler(Request(GET, "/"))
            assertEquals(OK, response.status)
            assertEquals("text/html; charset=utf-8", response.header("Content-Type"))
            assertTrue(response.bodyString().contains("<strong>$expected</strong>"))
        }

        assertCount(0)
        repeat(2) {
            val response = handler(Request(POST, "/visit"))
            assertEquals(SEE_OTHER, response.status)
            assertEquals("/", response.header("Location"))
        }
        assertCount(2)
        assertCount(2)
        assertTrue(Application().api()(Request(GET, "/")).bodyString().contains("<strong>0</strong>"))
    }

    @Test
    fun `logo is served as SVG`() {
        val response = Application().api()(Request(GET, "/logo.svg"))
        assertEquals(OK, response.status)
        assertEquals("image/svg+xml", response.header("Content-Type"))
        assertTrue(response.bodyString().contains("<svg"))
    }

    @Test
    fun `health check returns no content`() {
        val response = Application().api()(Request(GET, "/healthz"))
        assertEquals(NO_CONTENT, response.status)
        assertEquals("", response.bodyString())
    }

    @Test
    fun `unknown paths are not served`() {
        assertEquals(NOT_FOUND, Application().api()(Request(GET, "/unknown")).status)
    }
}
