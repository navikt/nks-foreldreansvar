package no.nav.nks.foreldreansvar.salesforce

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.slf4j.LoggerFactory

/**
 * Talks to Salesforce's standard REST API (no Apex of our own): finds open threads with a SOQL query and closes them
 * with sObject Collections. If Salesforce rejects the token (401), we get a new one and try once more.
 *
 * Never log the query, the request URL or a response body: they contain idents. Ktor puts the URL in the message of
 * timeout exceptions, so callers must not log exception messages from here either (the Leesah consumer only logs the
 * exception class).
 */
class SalesforceClient(
    private val httpClient: HttpClient,
    private val config: SalesforceConfig,
    private val tokenClient: SalesforceTokenClient,
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
) {
    private val log = LoggerFactory.getLogger(SalesforceClient::class.java)
    private val dataPath = "/services/data/v${config.apiVersion}"

    /**
     * All open threads of type [SalesforceConfig.threadType] on these children. Idents that are not 11 digits are left
     * out of the query (they cannot match, and this keeps anything but digits out of the SOQL).
     */
    suspend fun findOpenThreads(barnIdenter: Collection<String>): List<OpenThread> {
        val gyldige = barnIdenter.filter { IDENT_REGEX.matches(it) }.distinct()
        if (gyldige.size < barnIdenter.distinct().size) {
            log.warn("Left {} idents that are not 11 digits out of the Salesforce query", barnIdenter.distinct().size - gyldige.size)
        }
        if (gyldige.isEmpty()) return emptyList()

        val soql = openThreadsSoql(config.threadType, gyldige)
        val threads = mutableListOf<OpenThread>()
        // The first page comes from /query?q=...; if there are more, Salesforce gives a path to the next page.
        var nextRecordsUrl: String? = null
        do {
            val next = nextRecordsUrl
            val response =
                call("query") { token ->
                    if (next == null) {
                        httpClient.get("${token.instanceUrl}$dataPath/query") {
                            bearerAuth(token.accessToken)
                            parameter("q", soql)
                        }
                    } else {
                        httpClient.get("${token.instanceUrl}$next") { bearerAuth(token.accessToken) }
                    }
                }
            val page = response.body<QueryResponse>()
            page.records.mapTo(threads) {
                OpenThread(
                    id = it.id,
                    barnIdent = it.account?.personIdent,
                    forelderIdent = it.contact?.account?.personIdent,
                )
            }
            nextRecordsUrl = page.nextRecordsUrl.takeUnless { page.done }
        } while (nextRecordsUrl != null)
        return threads
    }

    /**
     * Closes threads the same way as the close button in Salesforce: `CRM_isActive__c = false` and `CRM_Closed_By__c`.
     * Sends at most [MAX_RECORDS_PER_REQUEST] per request with `allOrNone`, so a request either closes all its threads
     * or none. Throws if any thread was not closed. Retrying is safe: closed threads are not found again.
     */
    suspend fun closeThreads(threadIds: List<String>) {
        val closedBy = requireNotNull(config.closedBy) { "SALESFORCE_CLOSED_BY is not set" }
        threadIds.chunked(MAX_RECORDS_PER_REQUEST).forEach { chunk ->
            val body =
                mapOf(
                    "allOrNone" to true,
                    "records" to
                        chunk.map { id ->
                            mapOf(
                                "attributes" to mapOf("type" to "Thread__c"),
                                "id" to id,
                                "CRM_isActive__c" to false,
                                "CRM_Closed_By__c" to closedBy,
                            )
                        },
                )
            val response =
                call("close") { token ->
                    httpClient.patch("${token.instanceUrl}$dataPath/composite/sobjects") {
                        bearerAuth(token.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
            // Salesforce answers 200 even when records fail, so we must check each result.
            val results = response.body<List<SaveResult>>()
            val failed = results.filterNot { it.success }
            if (failed.isNotEmpty() || results.size != chunk.size) {
                // Only the error codes: the messages may come from validation rules and could contain anything.
                val codes = failed.flatMap { r -> r.errors.mapNotNull { it.statusCode } }.distinct()
                throw SalesforceException(
                    "Salesforce did not close ${failed.size} of ${chunk.size} threads (got ${results.size} results): $codes",
                )
            }
        }
    }

    /** Runs one HTTP call with the current token. On 401 it gets a new token and tries once more. Throws on non-2xx. */
    private suspend fun call(
        operation: String,
        request: suspend (SalesforceToken) -> HttpResponse,
    ): HttpResponse {
        var response = meterRegistry.timeSalesforce(operation) { request(tokenClient.token()) }
        if (response.status == HttpStatusCode.Unauthorized) {
            log.info("Salesforce rejected the access token, getting a new one")
            response = meterRegistry.timeSalesforce(operation) { request(tokenClient.token(forceNew = true)) }
        }
        if (!response.status.isSuccess()) {
            // The response body may echo idents, so only the status is logged.
            throw SalesforceException("Salesforce $operation returned HTTP ${response.status.value}")
        }
        return response
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class QueryResponse(
        val done: Boolean = true,
        val nextRecordsUrl: String? = null,
        val records: List<ThreadRecord> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ThreadRecord(
        @JsonProperty("Id") val id: String,
        @JsonProperty("CRM_Account__r") val account: AccountRecord? = null,
        @JsonProperty("CRM_Contact__r") val contact: ContactRecord? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AccountRecord(
        @JsonProperty("INT_PersonIdent__c") val personIdent: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ContactRecord(
        @JsonProperty("Account") val account: AccountRecord? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SaveResult(
        val success: Boolean = false,
        val errors: List<SaveError> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SaveError(
        val statusCode: String? = null,
    )

    companion object {
        /** Salesforce's limit for one sObject Collections request. */
        const val MAX_RECORDS_PER_REQUEST = 200

        private val IDENT_REGEX = Regex("^\\d{11}$")

        /**
         * The SOQL for open threads of [threadType] on these children, with both the child's and the parent's ident.
         * [barnIdenter] must be 11-digit idents; [threadType] is escaped, since it is put inside quotes.
         */
        internal fun openThreadsSoql(
            threadType: String,
            barnIdenter: Collection<String>,
        ): String {
            require(barnIdenter.isNotEmpty()) { "No idents" }
            require(barnIdenter.all { IDENT_REGEX.matches(it) }) { "Only 11-digit idents may be put in the query" }
            return "SELECT Id, CRM_Account__r.INT_PersonIdent__c, CRM_Contact__r.Account.INT_PersonIdent__c " +
                "FROM Thread__c " +
                "WHERE CRM_isActive__c = true " +
                "AND CRM_Thread_Type__c = '${escapeSoql(threadType)}' " +
                "AND CRM_Account__r.INT_PersonIdent__c IN (${barnIdenter.joinToString(",") { "'$it'" }})"
        }

        /** Escapes a value for use inside single quotes in SOQL: backslash first, then the quote. */
        internal fun escapeSoql(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    }
}
