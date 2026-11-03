package no.nav.nks.foreldreansvar

import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.nks.foreldreansvar.leesah.AnsvarligeSync
import no.nav.nks.foreldreansvar.leesah.ConsumerHealth
import no.nav.nks.foreldreansvar.leesah.LeesahConfig
import no.nav.nks.foreldreansvar.leesah.LeesahConsumer
import no.nav.nks.foreldreansvar.pdl.PdlClient
import no.nav.nks.foreldreansvar.pdl.PdlConfig
import no.nav.nks.foreldreansvar.pdl.TexasTokenClient
import no.nav.nks.foreldreansvar.pdl.createHttpClient
import no.nav.nks.foreldreansvar.salesforce.DryRunSender
import no.nav.nks.foreldreansvar.salesforce.SalesforceClient
import no.nav.nks.foreldreansvar.salesforce.SalesforceConfig
import no.nav.nks.foreldreansvar.salesforce.SalesforceTokenClient

fun main() {
    // Read config before starting the server so a missing variable stops the app immediately.
    val azureConfig = AzureConfig.fromEnv()
    val pdlConfig = PdlConfig.fromEnv()
    val leesahConfig = LeesahConfig.fromEnv()
    val salesforceConfig = SalesforceConfig.fromEnv()

    // One registry for all metrics, served at /internal/metrics.
    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    // One shared HTTP client for the whole app; it holds connection pools and must be closed on shutdown.
    val httpClient = createHttpClient()
    val pdlClient = PdlClient(httpClient, pdlConfig, TexasTokenClient(httpClient, pdlConfig.tokenEndpoint), meterRegistry)

    // SALESFORCE_ENABLED=false gives a dry run: we still look up PDL for each event, but only log what we would send.
    val sender =
        salesforceConfig?.let {
            SalesforceClient(httpClient, it, SalesforceTokenClient(httpClient, it, meterRegistry), meterRegistry)
        } ?: DryRunSender()

    // Null when LEESAH_ENABLED=false.
    val consumerHealth = ConsumerHealth()
    val leesahConsumer =
        leesahConfig?.let {
            LeesahConsumer.create(
                it,
                AnsvarligeSync(pdlClient, sender, meterRegistry),
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
