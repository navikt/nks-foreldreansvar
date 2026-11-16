package no.nav.nks.foreldreansvar.salesforce

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import no.nav.nks.foreldreansvar.AnsvarligDto
import no.nav.nks.foreldreansvar.respondJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SalesforceThreadCloserTest {
    private val mapper = jacksonObjectMapper()

    // Synthetic test idents, not real people.
    private val barn = "10101012345"
    private val mor = "01020312345"
    private val far = "05060712345"

    private val oppdatering =
        ForeldreansvarOppdatering(barn, listOf(AnsvarligDto(mor, "felles")), "hendelse-1", "2026-10-29T10:00:00Z")

    private fun fakeSalesforce() =
        FakeSalesforce { request, body ->
            if (request.method == HttpMethod.Patch) {
                respondJson(allClosed(body))
            } else {
                respondJson(queryPage(Triple("t-mor", barn, mor), Triple("t-far", barn, far), Triple("t-ukjent", barn, null)))
            }
        }

    private fun FakeSalesforce.counted(
        name: String,
        dryRun: Boolean,
    ) = meterRegistry
        .find(name)
        .tags("dry_run", dryRun.toString())
        .counter()
        ?.count() ?: 0.0

    @Test
    fun `closes the threads of parents who are no longer ansvarlige`() =
        runBlocking {
            val salesforce = fakeSalesforce()

            SalesforceThreadCloser(salesforce.client, lukkEnabled = true, salesforce.meterRegistry).closeThreads(listOf(oppdatering))

            val patch = mapper.readTree(salesforce.apiBodies.last())
            assertEquals(listOf("t-far"), patch["records"].map { it["id"].asText() })
            assertEquals(3.0, salesforce.counted("foreldreansvar.threads.found", dryRun = false))
            assertEquals(1.0, salesforce.counted("foreldreansvar.threads.closed", dryRun = false))
            assertEquals(1.0, salesforce.counted("foreldreansvar.threads.without_parent", dryRun = false))
        }

    @Test
    fun `dry run finds and counts the threads but sends no PATCH`() =
        runBlocking {
            val salesforce = fakeSalesforce()
            val closer = SalesforceThreadCloser(salesforce.client, lukkEnabled = false, salesforce.meterRegistry)

            closer.closeThreads(listOf(oppdatering))

            assertEquals(true, closer.dryRun)
            assertEquals(1, salesforce.queries.size)
            assertEquals(emptyList<Any>(), salesforce.closeRequests)
            assertEquals(1.0, salesforce.counted("foreldreansvar.threads.closed", dryRun = true))
            assertEquals(0.0, salesforce.counted("foreldreansvar.threads.closed", dryRun = false))
        }

    @Test
    fun `sends no PATCH when there is nothing to close`() =
        runBlocking {
            val salesforce = FakeSalesforce()

            SalesforceThreadCloser(salesforce.client, lukkEnabled = true, salesforce.meterRegistry).closeThreads(listOf(oppdatering))

            assertEquals(1, salesforce.apiRequests.size)
            assertEquals(emptyList<Any>(), salesforce.closeRequests)
        }
}
