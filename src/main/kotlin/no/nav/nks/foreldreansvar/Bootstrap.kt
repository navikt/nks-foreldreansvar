package no.nav.nks.foreldreansvar

import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import no.nav.nks.foreldreansvar.pdl.PdlClient
import no.nav.nks.foreldreansvar.pdl.PdlConfig
import no.nav.nks.foreldreansvar.pdl.TexasTokenClient
import no.nav.nks.foreldreansvar.pdl.createHttpClient

fun main() {
    // Read config before starting the server so a missing variable stops the app immediately.
    val azureConfig = AzureConfig.fromEnv()
    val pdlConfig = PdlConfig.fromEnv()

    // One shared HTTP client for the whole app; it holds connection pools and must be closed on shutdown.
    val httpClient = createHttpClient()
    val pdlClient = PdlClient(httpClient, pdlConfig, TexasTokenClient(httpClient, pdlConfig.tokenEndpoint))

    embeddedServer(Netty, port = 8080) {
        monitor.subscribe(ApplicationStopped) { httpClient.close() }
        module(azureConfig, pdlClient)
    }.start(wait = true)
}
