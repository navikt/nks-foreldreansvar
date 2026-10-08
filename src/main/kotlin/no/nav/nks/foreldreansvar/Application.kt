package no.nav.nks.foreldreansvar

import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.NO_CONTENT
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.SEE_OTHER
import org.http4k.routing.bind
import org.http4k.routing.routes
import java.util.concurrent.atomic.AtomicLong

class Application {
    private val count = AtomicLong()
    private val page = resource("/index.html")
    private val logo = resource("/logo.svg")

    fun api(): HttpHandler =
        routes(
            "/" bind GET to {
                Response(OK)
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body(page.replace("{{count}}", count.get().toString()))
            },
            "/visit" bind POST to {
                count.incrementAndGet()
                Response(SEE_OTHER).header("Location", "/")
            },
            "/logo.svg" bind GET to {
                Response(OK).header("Content-Type", "image/svg+xml").body(logo)
            },
            "/healthz" bind GET to { Response(NO_CONTENT) },
        )

    private fun resource(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing application resource: $path" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
}
