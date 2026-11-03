package no.nav.nks.foreldreansvar.salesforce

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.nks.foreldreansvar.AnsvarligDto
import org.slf4j.LoggerFactory

/**
 * One element in the request to Salesforce: the full current state for one child.
 * An empty [ansvarlige] means "remove all sharing for this child". Sending the same element twice is harmless.
 */
data class ForeldreansvarOppdatering(
    val barnIdent: String,
    val ansvarlige: List<AnsvarligDto>,
    /** The Leesah event that triggered the lookup (the latest one, if several arrived for the child). For tracing. */
    val hendelseId: String,
    /** When we looked the child up in PDL, ISO-8601 in UTC. */
    val hentetTidspunkt: String,
) {
    override fun toString(): String =
        "ForeldreansvarOppdatering(barnIdent=<masked>, ansvarlige=${ansvarlige.size}, hendelseId=$hendelseId, " +
            "hentetTidspunkt=$hentetTidspunkt)"
}

/** The request body. Wrapped in an object so an Apex `doPost(List<Oppdatering> oppdateringer)` binds it directly. */
data class ForeldreansvarRequest(
    val oppdateringer: List<ForeldreansvarOppdatering>,
)

/** Where a batch of up to [MAX_BATCH_SIZE] elements goes. Throws if it was not accepted, so the poll is retried. */
fun interface OppdateringSender {
    suspend fun send(oppdateringer: List<ForeldreansvarOppdatering>)

    /** True when nothing is really sent. Used as a metric tag, so dry-run numbers are not mistaken for real ones. */
    val dryRun: Boolean get() = false

    companion object {
        const val MAX_BATCH_SIZE = 50
    }
}

/** Used while `SALESFORCE_ENABLED=false`: sends nothing, only logs counts. */
class DryRunSender : OppdateringSender {
    override val dryRun = true
    private val log = LoggerFactory.getLogger(DryRunSender::class.java)

    override suspend fun send(oppdateringer: List<ForeldreansvarOppdatering>) {
        log.info(
            "Salesforce disabled (dry run): would send {} children, {} with no ansvarlige",
            oppdateringer.size,
            oppdateringer.count { it.ansvarlige.isEmpty() },
        )
    }
}

/**
 * Posts batches to our Apex REST endpoint. Any 2xx means Salesforce accepted the whole batch.
 * If Salesforce rejects the token (401), we get a new one and try once more.
 */
class SalesforceClient(
    private val httpClient: HttpClient,
    private val config: SalesforceConfig,
    private val tokenClient: SalesforceTokenClient,
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
) : OppdateringSender {
    private val log = LoggerFactory.getLogger(SalesforceClient::class.java)

    override suspend fun send(oppdateringer: List<ForeldreansvarOppdatering>) {
        require(oppdateringer.size <= OppdateringSender.MAX_BATCH_SIZE) { "Batch too large: ${oppdateringer.size}" }
        val body = ForeldreansvarRequest(oppdateringer)
        var response = post(body, tokenClient.token())
        if (response.status == HttpStatusCode.Unauthorized) {
            log.info("Salesforce rejected the access token, getting a new one")
            response = post(body, tokenClient.token(forceNew = true))
        }
        if (!response.status.isSuccess()) {
            // The response body may echo idents, so only the status is logged.
            throw SalesforceException("Salesforce returned HTTP ${response.status.value} for a batch of ${oppdateringer.size}")
        }
        log.info("Sent {} children to Salesforce", oppdateringer.size)
    }

    private suspend fun post(
        body: ForeldreansvarRequest,
        token: SalesforceToken,
    ): HttpResponse =
        meterRegistry.timeSalesforce("send") {
            httpClient.post("${token.instanceUrl}${config.endpointPath}") {
                bearerAuth(token.accessToken)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
}
