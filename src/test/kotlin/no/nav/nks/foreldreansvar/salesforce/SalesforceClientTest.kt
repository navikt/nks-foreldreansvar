package no.nav.nks.foreldreansvar.salesforce

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import no.nav.nks.foreldreansvar.AnsvarligDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SalesforceClientTest {
    private val mapper = jacksonObjectMapper()

    private val oppdatering =
        ForeldreansvarOppdatering(
            barnIdent = "10101012345",
            ansvarlige = listOf(AnsvarligDto("01020312345", "felles")),
            hendelseId = "hendelse-1",
            hentetTidspunkt = "2026-10-29T10:00:00Z",
        )

    @Test
    fun `posts the batch to the Apex endpoint on the instance with the access token`() =
        runBlocking {
            val salesforce = FakeSalesforce()

            salesforce.client.send(listOf(oppdatering, oppdatering.copy(barnIdent = "20202012345", ansvarlige = emptyList())))

            val request = salesforce.apexRequests.single()
            assertEquals("$TEST_INSTANCE_URL/services/apexrest/foreldreansvar/v1", request.url.toString())
            assertEquals("Bearer token-1", request.headers[HttpHeaders.Authorization])
            val expected =
                """{"oppdateringer":[
                  {"barnIdent":"10101012345","ansvarlige":[{"ident":"01020312345","ansvar":"felles"}],
                   "hendelseId":"hendelse-1","hentetTidspunkt":"2026-10-29T10:00:00Z"},
                  {"barnIdent":"20202012345","ansvarlige":[],
                   "hendelseId":"hendelse-1","hentetTidspunkt":"2026-10-29T10:00:00Z"}]}"""
            assertEquals(mapper.readTree(expected), mapper.readTree(salesforce.apexBodies.single()))
            assertEquals(1, salesforce.requests("token", "ok"))
            assertEquals(1, salesforce.requests("send", "ok"))
        }

    @Test
    fun `gets a new token once when Salesforce answers 401`() =
        runBlocking {
            val salesforce =
                FakeSalesforce { request ->
                    if (request.headers[HttpHeaders.Authorization] == "Bearer token-1") {
                        respond("", HttpStatusCode.Unauthorized)
                    } else {
                        respond("", HttpStatusCode.OK)
                    }
                }

            salesforce.client.send(listOf(oppdatering))

            assertEquals(2, salesforce.tokenRequests.size)
            assertEquals(2, salesforce.apexRequests.size)
            assertEquals(1, salesforce.requests("send", "unauthorized"))
            assertEquals(1, salesforce.requests("send", "ok"))
        }

    @Test
    fun `throws on an error status without putting idents in the message`() {
        val salesforce = FakeSalesforce { respond("""{"error":"10101012345"}""", HttpStatusCode.InternalServerError) }

        val e = assertThrows<SalesforceException> { runBlocking { salesforce.client.send(listOf(oppdatering)) } }

        assertEquals("Salesforce returned HTTP 500 for a batch of 1", e.message)
        assertFalse(e.message!!.contains("10101012345"))
        assertEquals(1, salesforce.requests("send", "server_error"))
    }

    @Test
    fun `refuses batches larger than 50`() {
        val salesforce = FakeSalesforce()
        assertThrows<IllegalArgumentException> { runBlocking { salesforce.client.send(List(51) { oppdatering }) } }
        assertEquals(0, salesforce.apexRequests.size)
    }
}
