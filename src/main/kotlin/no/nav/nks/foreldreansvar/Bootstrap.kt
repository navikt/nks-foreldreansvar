package no.nav.nks.foreldreansvar

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    // Read config before starting the server so a missing variable stops the app immediately.
    val azureConfig = AzureConfig.fromEnv()
    embeddedServer(Netty, port = 8080) { module(azureConfig) }.start(wait = true)
}
