package no.nav.nks.foreldreansvar

import org.http4k.server.Netty
import org.http4k.server.asServer
import org.slf4j.LoggerFactory

fun main() {
    val server = Application().api().asServer(Netty(8080))
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    server.start()
    LoggerFactory.getLogger("Bootstrap").info("Listening on port {}", server.port())
}
