package no.nav.nks.foreldreansvar.leesah

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.nks.foreldreansvar.AnsvarligDto
import no.nav.nks.foreldreansvar.BESKYTTET
import no.nav.nks.foreldreansvar.FakePdl
import no.nav.nks.foreldreansvar.pdl.PdlException
import no.nav.nks.foreldreansvar.pdlResource
import no.nav.nks.foreldreansvar.respondJson
import no.nav.nks.foreldreansvar.salesforce.DryRunSender
import no.nav.nks.foreldreansvar.salesforce.ForeldreansvarOppdatering
import no.nav.nks.foreldreansvar.salesforce.OppdateringSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AnsvarligeSyncTest {
    private val mapper = jacksonObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC)
    private val tidspunkt = "2026-10-29T10:00:00Z"

    // Synthetic test idents, not real people.
    private val barn = "10101012345"
    private val mor = "01020312345"
    private val far = "05060712345"

    private val sent = mutableListOf<List<ForeldreansvarOppdatering>>()
    private val sender = OppdateringSender { sent += it }
    private val meterRegistry = SimpleMeterRegistry()

    private fun synced(
        result: String,
        dryRun: Boolean = false,
    ) = meterRegistry
        .find("foreldreansvar.sync.children")
        .tags("result", result, "dry_run", dryRun.toString())
        .counter()
        ?.count() ?: 0.0

    private fun hendelse(
        fnr: String? = barn,
        hendelseId: String = "hendelse-1",
        opprettet: String = "2026-10-29T09:00:00Z",
    ) = Personhendelse(
        hendelseId = hendelseId,
        personidenter = listOfNotNull("1234567891011", fnr),
        opplysningstype = "FORELDREANSVAR_V1",
        endringstype = "OPPRETTET",
        tidligereHendelseId = null,
        opprettet = Instant.parse(opprettet),
    )

    private fun barnJson(
        gradering: String? = null,
        foreldreansvar: String = """[{"ansvar":"felles","ansvarlig":null,"ansvarssubjekt":"$barn"}]""",
    ): String {
        val adressebeskyttelse = gradering?.let { """[{"gradering":"$it"}]""" } ?: "[]"
        return """{"data":{"hentBarn":{
            "adressebeskyttelse":$adressebeskyttelse,
            "foreldreansvar":$foreldreansvar,
            "forelderBarnRelasjon":[
              {"relatertPersonsIdent":"$mor","relatertPersonsRolle":"MOR"},
              {"relatertPersonsIdent":"$far","relatertPersonsRolle":"FAR"}
            ]}}}"""
    }

    private fun bolk(vararg personer: Pair<String, String>): String =
        personer.joinToString(",", """{"data":{"hentPersonBolk":[""", "]}}") { (ident, gradering) ->
            """{"ident":"$ident","code":"ok","person":{"adressebeskyttelse":[{"gradering":"$gradering"}]}}"""
        }

    /** The ident a hentBarn request asked for. */
    private fun FakePdl.barnIdenter() =
        pdlBodies.filter { it.contains("barnAnsvarligeQuery") }.map { mapper.readTree(it)["variables"]["ident"].asText() }

    @Test
    fun `looks up each child in PDL and sends the full state`() {
        val pdl = FakePdl(bolk = { respondJson(bolk(mor to "UGRADERT", far to "UGRADERT")) }) { respondJson(barnJson()) }

        AnsvarligeSync(pdl.client, sender, meterRegistry, clock).handle(listOf(hendelse()))

        val expected =
            ForeldreansvarOppdatering(barn, listOf(AnsvarligDto(mor, "felles"), AnsvarligDto(far, "felles")), "hendelse-1", tidspunkt)
        assertEquals(listOf(listOf(expected)), sent)
        assertEquals(1.0, synced("with_ansvarlige"))
    }

    @Test
    fun `several events for the same child give one lookup with the latest hendelseId`() {
        val pdl = FakePdl { respondJson(barnJson(foreldreansvar = "[]")) }

        AnsvarligeSync(pdl.client, sender, meterRegistry, clock).handle(
            listOf(
                hendelse(hendelseId = "gammel", opprettet = "2026-10-29T08:00:00Z"),
                hendelse(hendelseId = "ny", opprettet = "2026-10-29T09:30:00Z"),
                hendelse(hendelseId = "midt", opprettet = "2026-10-29T09:00:00Z"),
                hendelse(fnr = null, hendelseId = "uten-fnr"),
            ),
        )

        assertEquals(listOf(barn), pdl.barnIdenter())
        assertEquals(listOf("ny"), sent.single().map { it.hendelseId })
    }

    @Test
    fun `splits into batches of 50`() {
        val pdl = FakePdl { respondJson(barnJson(foreldreansvar = "[]")) }
        val hendelser = (1..120).map { hendelse(fnr = "%011d".format(it), hendelseId = "h$it") }

        AnsvarligeSync(pdl.client, sender, meterRegistry, clock).handle(hendelser)

        assertEquals(listOf(50, 50, 20), sent.map { it.size })
        assertEquals(
            120,
            sent
                .flatten()
                .map { it.barnIdent }
                .toSet()
                .size,
        )
    }

    @Test
    fun `a child not found in PDL is sent with no ansvarlige`() {
        val pdl = FakePdl { respondJson(pdlResource("not-found.json")) }

        AnsvarligeSync(pdl.client, sender, meterRegistry, clock).handle(listOf(hendelse()))

        assertEquals(listOf(listOf(ForeldreansvarOppdatering(barn, emptyList(), "hendelse-1", tidspunkt))), sent)
        assertEquals(1.0, synced("not_found"))
    }

    @Test
    fun `a child with kode 6 or 7 is sent with no ansvarlige, without looking up the parents`() {
        BESKYTTET.forEach { gradering ->
            sent.clear()
            val pdl = FakePdl { respondJson(barnJson(gradering)) }

            AnsvarligeSync(pdl.client, sender, meterRegistry, clock).handle(listOf(hendelse()))

            assertEquals(emptyList<AnsvarligDto>(), sent.single().single().ansvarlige, gradering)
            assertTrue(pdl.pdlBodies.none { it.contains("hentPersonBolkQuery") })
        }
    }

    @Test
    fun `a parent with kode 6 or 7 is left out`() {
        val pdl = FakePdl(bolk = { respondJson(bolk(mor to "STRENGT_FORTROLIG", far to "UGRADERT")) }) { respondJson(barnJson()) }

        AnsvarligeSync(pdl.client, sender, meterRegistry, clock).handle(listOf(hendelse()))

        assertEquals(listOf(AnsvarligDto(far, "felles")), sent.single().single().ansvarlige)
    }

    @Test
    fun `other PDL errors fail the whole poll and nothing is sent`() {
        val pdl = FakePdl { respond("", HttpStatusCode.InternalServerError) }

        assertThrows<PdlException> { AnsvarligeSync(pdl.client, sender, meterRegistry, clock).handle(listOf(hendelse())) }
        assertEquals(emptyList<List<ForeldreansvarOppdatering>>(), sent)
        assertEquals(null, meterRegistry.find("foreldreansvar.sync.children").counter())
    }

    @Test
    fun `dry run is tagged so its numbers are not mistaken for real deliveries`() {
        val pdl = FakePdl { respondJson(barnJson(foreldreansvar = "[]")) }

        AnsvarligeSync(pdl.client, DryRunSender(), meterRegistry, clock).handle(listOf(hendelse()))

        assertEquals(1.0, synced("without_ansvarlige", dryRun = true))
        assertEquals(0.0, synced("without_ansvarlige", dryRun = false))
    }
}
