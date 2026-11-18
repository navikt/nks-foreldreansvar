package no.nav.nks.foreldreansvar.salesforce

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.nks.foreldreansvar.pdl.createHttpClient
import no.nav.nks.foreldreansvar.respondJson
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
        apiVersion = "62.0",
        threadType = "BRUKERVARSEL",
        lukkEnabled = true,
        closedBy = "nks-foreldreansvar",
        clientId = "consumer-key",
        username = "integrasjon@nav.test",
        privateKeyPem = testPrivateKeyPem,
    )

const val TEST_INSTANCE_URL = "https://nav.my.salesforce.test"

/**
 * A real [SalesforceClient] whose HTTP calls go to a fake Salesforce. Records the token requests and every other
 * request with its body. [api] decides the answer to the other requests; by default a query finds no threads and
 * every thread in a PATCH is closed.
 */
class FakeSalesforce(
    private val tokenStatus: HttpStatusCode = HttpStatusCode.OK,
    val config: SalesforceConfig = testSalesforceConfig,
    private val api: suspend MockRequestHandleScope.(HttpRequestData, String) -> HttpResponseData = { request, body ->
        defaultAnswer(request, body)
    },
) {
    val tokenRequests = mutableListOf<String>()
    val apiRequests = mutableListOf<HttpRequestData>()
    val apiBodies = mutableListOf<String>()
    private var tokenNumber = 0

    /** The PATCH requests to sObject Collections. */
    val closeRequests get() = apiRequests.filter { it.method == HttpMethod.Patch }

    /** The SOQL of each query request (the first page of a query). */
    val queries get() = apiRequests.mapNotNull { it.url.parameters["q"] }

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
                val body = request.body.toByteArray().decodeToString()
                apiRequests += request
                apiBodies += body
                api(request, body)
            }
        }

    private val httpClient = createHttpClient(engine)
    val meterRegistry = SimpleMeterRegistry()
    val tokenClient = SalesforceTokenClient(httpClient, testSalesforceConfig, meterRegistry)
    val client = SalesforceClient(httpClient, config, tokenClient, meterRegistry)

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

private val mapper = jacksonObjectMapper()

/** A query page in Salesforce's format. Each thread is (Id, child ident, parent ident). */
fun queryPage(
    vararg threads: Triple<String, String?, String?>,
    nextRecordsUrl: String? = null,
): String {
    val records =
        threads.map { (id, barn, forelder) ->
            mapOf(
                "attributes" to mapOf("type" to "Thread__c"),
                "Id" to id,
                "CRM_Account__r" to barn?.let { mapOf("INT_PersonIdent__c" to it) },
                "CRM_Contact__r" to forelder?.let { mapOf("Account" to mapOf("INT_PersonIdent__c" to it)) },
            )
        }
    return mapper.writeValueAsString(
        mapOf("totalSize" to records.size, "done" to (nextRecordsUrl == null), "nextRecordsUrl" to nextRecordsUrl, "records" to records),
    )
}

/** sObject Collections results: one success per record in the PATCH body. */
fun allClosed(patchBody: String): String =
    mapper.writeValueAsString(
        mapper.readTree(patchBody)["records"].map { mapOf("id" to it["id"].asText(), "success" to true, "errors" to emptyList<Any>()) },
    )

private fun MockRequestHandleScope.defaultAnswer(
    request: HttpRequestData,
    body: String,
): HttpResponseData =
    when (request.method) {
        HttpMethod.Patch -> respondJson(allClosed(body))
        else -> respondJson(queryPage())
    }
