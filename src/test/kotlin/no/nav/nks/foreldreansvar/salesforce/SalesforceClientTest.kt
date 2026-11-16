package no.nav.nks.foreldreansvar.salesforce

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import no.nav.nks.foreldreansvar.respondJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SalesforceClientTest {
    private val mapper = jacksonObjectMapper()

    // Synthetic test idents, not real people.
    private val barn1 = "10101012345"
    private val barn2 = "20202012345"
    private val mor = "01020312345"

    @Test
    fun `finds open threads with a SOQL query on the instance`() =
        runBlocking {
            val salesforce =
                FakeSalesforce { _, _ ->
                    respondJson(queryPage(Triple("a01", barn1, mor), Triple("a02", barn2, null)))
                }

            val threads = salesforce.client.findOpenThreads(listOf(barn1, barn2))

            assertEquals(listOf(OpenThread("a01", barn1, mor), OpenThread("a02", barn2, null)), threads)
            val request = salesforce.apiRequests.single()
            assertEquals("$TEST_INSTANCE_URL/services/data/v62.0/query", request.url.toString().substringBefore('?'))
            assertEquals("Bearer token-1", request.headers[HttpHeaders.Authorization])
            assertEquals(
                "SELECT Id, CRM_Account__r.INT_PersonIdent__c, CRM_Contact__r.Account.INT_PersonIdent__c FROM Thread__c " +
                    "WHERE CRM_isActive__c = true AND CRM_Thread_Type__c = 'BRUKERVARSEL' " +
                    "AND CRM_Account__r.INT_PersonIdent__c IN ('$barn1','$barn2')",
                salesforce.queries.single(),
            )
            assertEquals(1, salesforce.requests("query", "ok"))
        }

    @Test
    fun `follows nextRecordsUrl until the last page`() =
        runBlocking {
            val salesforce =
                FakeSalesforce { request, _ ->
                    when (request.url.encodedPath) {
                        "/services/data/v62.0/query" -> {
                            respondJson(queryPage(Triple("a01", barn1, mor), nextRecordsUrl = "/services/data/v62.0/query/01g-2000"))
                        }

                        "/services/data/v62.0/query/01g-2000" -> {
                            respondJson(queryPage(Triple("a02", barn1, mor), nextRecordsUrl = "/services/data/v62.0/query/01g-4000"))
                        }

                        else -> {
                            respondJson(queryPage(Triple("a03", barn1, mor)))
                        }
                    }
                }

            val threads = salesforce.client.findOpenThreads(listOf(barn1))

            assertEquals(listOf("a01", "a02", "a03"), threads.map { it.id })
            assertEquals(
                listOf(
                    "$TEST_INSTANCE_URL/services/data/v62.0/query/01g-2000",
                    "$TEST_INSTANCE_URL/services/data/v62.0/query/01g-4000",
                ),
                salesforce.apiRequests.drop(1).map { it.url.toString() },
            )
        }

    @Test
    fun `only 11-digit idents go into the query, and none means no query`() =
        runBlocking {
            val salesforce = FakeSalesforce()

            salesforce.client.findOpenThreads(listOf(barn1, "1' OR Name != '", "123"))
            assertEquals(listOf(barn1), Regex("'(\\d{11})'").findAll(salesforce.queries.single()).map { it.groupValues[1] }.toList())

            salesforce.client.findOpenThreads(listOf("abc"))
            assertEquals(1, salesforce.apiRequests.size)
        }

    @Test
    fun `escapes the thread type in the query`() {
        val soql = SalesforceClient.openThreadsSoql("a'b\\c", listOf(barn1))
        assertEquals(true, soql.contains("CRM_Thread_Type__c = 'a\\'b\\\\c'"))
        assertThrows<IllegalArgumentException> { SalesforceClient.openThreadsSoql("X", listOf("1' OR '1'='1")) }
    }

    @Test
    fun `closes threads with sObject Collections in chunks of 200`() =
        runBlocking {
            val salesforce = FakeSalesforce()
            val ids = (1..250).map { "a%03d".format(it) }

            salesforce.client.closeThreads(ids)

            val patches = salesforce.closeRequests
            assertEquals(2, patches.size)
            patches.forEach {
                assertEquals("$TEST_INSTANCE_URL/services/data/v62.0/composite/sobjects", it.url.toString())
                assertEquals("Bearer token-1", it.headers[HttpHeaders.Authorization])
            }
            val bodies = salesforce.apiBodies.map { mapper.readTree(it) }
            assertEquals(listOf(200, 50), bodies.map { it["records"].size() })
            val expectedFirst =
                """{"attributes":{"type":"Thread__c"},"id":"a001","CRM_isActive__c":false,"CRM_Closed_By__c":"nks-foreldreansvar"}"""
            assertEquals(mapper.readTree(expectedFirst), bodies[0]["records"][0])
            assertEquals(true, bodies[0]["allOrNone"].asBoolean())
            assertEquals(2, salesforce.requests("close", "ok"))
        }

    @Test
    fun `gets a new token once when Salesforce answers 401`() =
        runBlocking {
            val salesforce =
                FakeSalesforce { request, _ ->
                    if (request.headers[HttpHeaders.Authorization] == "Bearer token-1") {
                        respond("", HttpStatusCode.Unauthorized)
                    } else {
                        respondJson(queryPage(Triple("a01", barn1, mor)))
                    }
                }

            val threads = salesforce.client.findOpenThreads(listOf(barn1))

            assertEquals(1, threads.size)
            assertEquals(2, salesforce.tokenRequests.size)
            assertEquals(2, salesforce.apiRequests.size)
            assertEquals(1, salesforce.requests("query", "unauthorized"))
            assertEquals(1, salesforce.requests("query", "ok"))
        }

    @Test
    fun `throws when a thread was not closed, without messages or idents`() {
        val salesforce =
            FakeSalesforce { _, _ ->
                respondJson(
                    """[
                      {"id":"a01","success":false,"errors":[{"statusCode":"ALL_OR_NONE_OPERATION_ROLLED_BACK","message":"rolled back"}]},
                      {"id":null,"success":false,"errors":[{"statusCode":"FIELD_CUSTOM_VALIDATION_EXCEPTION","message":"$barn1"}]}
                    ]""",
                )
            }

        val e = assertThrows<SalesforceException> { runBlocking { salesforce.client.closeThreads(listOf("a01", "a02")) } }

        assertEquals(
            "Salesforce did not close 2 of 2 threads (got 2 results): [ALL_OR_NONE_OPERATION_ROLLED_BACK, FIELD_CUSTOM_VALIDATION_EXCEPTION]",
            e.message,
        )
        assertFalse(e.message!!.contains(barn1))
    }

    @Test
    fun `throws on an error status without putting idents in the message`() {
        val salesforce = FakeSalesforce { _, _ -> respond("""{"error":"$barn1"}""", HttpStatusCode.InternalServerError) }

        val e = assertThrows<SalesforceException> { runBlocking { salesforce.client.findOpenThreads(listOf(barn1)) } }

        assertEquals("Salesforce query returned HTTP 500", e.message)
        assertEquals(1, salesforce.requests("query", "server_error"))
        assertEquals(HttpMethod.Get, salesforce.apiRequests.single().method)
    }
}
