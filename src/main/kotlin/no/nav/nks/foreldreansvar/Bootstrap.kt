package no.nav.nks.foreldreansvar

import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.nks.foreldreansvar.leesah.ConsumerHealth
import no.nav.nks.foreldreansvar.leesah.LeesahConfig
import no.nav.nks.foreldreansvar.leesah.LeesahConsumer
import no.nav.nks.foreldreansvar.leesah.LoggingForeldreansvarHendelseHandler
import no.nav.nks.foreldreansvar.pdl.PdlClient
import no.nav.nks.foreldreansvar.pdl.PdlConfig
import no.nav.nks.foreldreansvar.pdl.TexasTokenClient
import no.nav.nks.foreldreansvar.pdl.createHttpClient

fun main() {
    // Read config before starting the server so a missing variable stops the app immediately.
    val azureConfig = AzureConfig.fromEnv()
    val pdlConfig = PdlConfig.fromEnv()
    val leesahConfig = LeesahConfig.fromEnv()

    // One registry for all metrics, served at /internal/metrics.
    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    // One shared HTTP client for the whole app; it holds connection pools and must be closed on shutdown.
    val httpClient = createHttpClient()
    val pdlClient = PdlClient(httpClient, pdlConfig, TexasTokenClient(httpClient, pdlConfig.tokenEndpoint), meterRegistry)

    // Null when LEESAH_ENABLED=false. The handler is a logging placeholder until Salesforce notification (5.2) is built.
    val consumerHealth = ConsumerHealth()
    val leesahConsumer =
        leesahConfig?.let {
            LeesahConsumer.create(
                it,
                LoggingForeldreansvarHendelseHandler(),
                consumerHealth,
                meterRegistry,
            )
        }

    embeddedServer(Netty, port = 8080) {
        monitor.subscribe(ApplicationStarted) { leesahConsumer?.start() }
        // Stop the consumer while the app is still up, so it can finish its batch and commit.
        monitor.subscribe(ApplicationStopping) { leesahConsumer?.stop() }
        monitor.subscribe(ApplicationStopped) { httpClient.close() }
        module(
            azureConfig,
            pdlClient,
            isAlive = { leesahConsumer == null || consumerHealth.isAlive() },
            meterRegistry = meterRegistry,
        )
    }.start(wait = true)
}
