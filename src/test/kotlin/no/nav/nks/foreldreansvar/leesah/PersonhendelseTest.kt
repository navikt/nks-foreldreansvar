package no.nav.nks.foreldreansvar.leesah

import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import io.confluent.kafka.serializers.KafkaAvroSerializer
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.avro.generic.GenericRecordBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class PersonhendelseTest {
    private val scope = "personhendelse-test"
    private val serdeConfig = mapOf("schema.registry.url" to "mock://$scope", "specific.avro.reader" to false)

    @AfterEach
    fun clearRegistry() = MockSchemaRegistry.dropScope(scope)

    // Runs the record through real Avro bytes and the schema registry, the same way the consumer receives it.
    private fun roundtrip(record: GenericRecord): GenericRecord {
        val bytes = KafkaAvroSerializer().apply { configure(serdeConfig, false) }.serialize("pdl.leesah-v1", record)
        return KafkaAvroDeserializer().apply { configure(serdeConfig, false) }.deserialize("pdl.leesah-v1", bytes) as GenericRecord
    }

    @Test
    fun `maps fields from an Avro record`() {
        val hendelse = Personhendelse.fra(roundtrip(personhendelse()))

        assertEquals(
            Personhendelse(
                hendelseId = "hendelse-1",
                personidenter = listOf(TEST_AKTOER_ID, TEST_FNR),
                opplysningstype = "FORELDREANSVAR_V1",
                endringstype = "OPPHOERT",
                tidligereHendelseId = "hendelse-0",
                opprettet = Instant.ofEpochMilli(1_760_000_000_000),
                foreldreansvar = Foreldreansvar(ansvar = "felles", ansvarlig = TEST_ANSVARLIG_FNR),
            ),
            hendelse,
        )
    }

    @Test
    fun `tidligereHendelseId is optional`() {
        assertNull(Personhendelse.fra(roundtrip(personhendelse(tidligereHendelseId = null))).tidligereHendelseId)
    }

    @Test
    fun `fnr is the 11-digit ident, not the aktoer-id`() {
        assertEquals(TEST_FNR, Personhendelse.fra(personhendelse(personidenter = listOf(TEST_AKTOER_ID, TEST_FNR))).fnr)
        assertEquals(TEST_FNR, Personhendelse.fra(personhendelse(personidenter = listOf(TEST_FNR, TEST_AKTOER_ID))).fnr)
        assertNull(Personhendelse.fra(personhendelse(personidenter = listOf(TEST_AKTOER_ID))).fnr)
    }

    @Test
    fun `ansvarlig is optional`() {
        val hendelse = Personhendelse.fra(roundtrip(personhendelse(foreldreansvar = foreldreansvar(ansvar = "mor", ansvarlig = null))))

        assertEquals(Foreldreansvar(ansvar = "mor", ansvarlig = null), hendelse.foreldreansvar)
    }

    @Test
    fun `foreldreansvar is null when the field is empty`() {
        assertNull(Personhendelse.fra(roundtrip(personhendelse(foreldreansvar = null))).foreldreansvar)
    }

    @Test
    fun `foreldreansvar is null when the record has an older schema without the field`() {
        val oldSchema =
            Schema.createRecord(
                personhendelseSchema.name,
                null,
                personhendelseSchema.namespace,
                false,
                personhendelseSchema.fields.filter { it.name() != "foreldreansvar" }.map { Schema.Field(it, it.schema()) },
            )
        val record = GenericRecordBuilder(oldSchema)
        val full = personhendelse()
        oldSchema.fields.forEach { record.set(it.name(), full.get(it.name())) }

        val hendelse = Personhendelse.fra(roundtrip(record.build()))

        assertNull(hendelse.foreldreansvar)
        assertEquals("hendelse-1", hendelse.hendelseId)
    }

    @Test
    fun `toString never contains idents`() {
        val text = Personhendelse.fra(personhendelse()).toString()

        assertFalse(text.contains(TEST_FNR))
        assertFalse(text.contains(TEST_AKTOER_ID))
        assertFalse(text.contains(TEST_ANSVARLIG_FNR))
        assertTrue(text.contains("hendelse-1"))
        assertTrue(text.contains("ansvar=felles"))
    }

    @Test
    fun `filter matches only configured opplysningstype and endringstype`() {
        val filter = PersonhendelseFilter(setOf("FORELDREANSVAR_V1"), setOf("OPPHOERT", "ANNULLERT"))

        assertTrue(filter.matches(roundtrip(personhendelse(endringstype = "OPPHOERT"))))
        assertTrue(filter.matches(roundtrip(personhendelse(endringstype = "ANNULLERT"))))
        assertFalse(filter.matches(roundtrip(personhendelse(endringstype = "OPPRETTET"))))
        assertFalse(filter.matches(roundtrip(personhendelse(opplysningstype = "DOEDSFALL_V1"))))
    }

    @Test
    fun `filter with all endringstyper matches every change to foreldreansvar`() {
        val filter = PersonhendelseFilter(setOf("FORELDREANSVAR_V1"), setOf("OPPRETTET", "KORRIGERT", "OPPHOERT", "ANNULLERT"))

        listOf("OPPRETTET", "KORRIGERT", "OPPHOERT", "ANNULLERT").forEach {
            assertTrue(filter.matches(roundtrip(personhendelse(endringstype = it))), it)
        }
        assertFalse(filter.matches(roundtrip(personhendelse(opplysningstype = "DOEDSFALL_V1"))))
    }
}
