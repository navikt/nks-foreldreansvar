package no.nav.nks.foreldreansvar.salesforce

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.nks.foreldreansvar.pdl.createHttpClient
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64

/** A fresh RSA key pair per test run, so no key is checked in. */
val testKeyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

val testPrivateKeyPem: String =
    "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(testKeyPair.private.encoded) +
        "\n-----END PRIVATE KEY-----\n"

val testSalesforceConfig =
    SalesforceConfig(
        loginUrl = "https://login.salesforce.test",
        endpointPath = "/services/apexrest/foreldreansvar/v1",
        clientId = "consumer-key",
        username = "integrasjon@nav.test",
        privateKeyPem = testPrivateKeyPem,
    )

const val TEST_INSTANCE_URL = "https://nav.my.salesforce.test"

/**
 * A real [SalesforceClient] whose HTTP calls go to a fake Salesforce. Records the token requests and the bodies posted
 * to the Apex endpoint. [apex] decides the answer to each POST; by default every batch is accepted.
 */
class FakeSalesforce(
    private val tokenStatus: HttpStatusCode = HttpStatusCode.OK,
    private val apex: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("", HttpStatusCode.OK) },
) {
    val tokenRequests = mutableListOf<String>()
    val apexRequests = mutableListOf<HttpRequestData>()
    val apexBodies = mutableListOf<String>()
    private var tokenNumber = 0

    private val engine =
        MockEngine { request ->
            if (request.url.encodedPath == "/services/oauth2/token") {
                tokenRequests += request.body.toByteArray().decodeToString()
                tokenNumber++
                if (tokenStatus != HttpStatusCode.OK) return@MockEngine respond("", tokenStatus)
                respond(
                    """{"access_token":"token-$tokenNumber","instance_url":"$TEST_INSTANCE_URL","token_type":"Bearer"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                apexRequests += request
                apexBodies += request.body.toByteArray().decodeToString()
                apex(request)
            }
        }

    private val httpClient = createHttpClient(engine)
    val meterRegistry = SimpleMeterRegistry()
    val tokenClient = SalesforceTokenClient(httpClient, testSalesforceConfig, meterRegistry)
    val client = SalesforceClient(httpClient, testSalesforceConfig, tokenClient, meterRegistry)

    /** How many calls were timed as `salesforce_requests_seconds{operation, outcome}`. */
    fun requests(
        operation: String,
        outcome: String,
    ): Long =
        meterRegistry
            .find("salesforce.requests")
            .tags("operation", operation, "outcome", outcome)
            .timer()
            ?.count() ?: 0
}
