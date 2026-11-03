package no.nav.nks.foreldreansvar.leesah

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.nks.foreldreansvar.FakePdl
import no.nav.nks.foreldreansvar.respondJson
import no.nav.nks.foreldreansvar.salesforce.FakeSalesforce
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.RecordDeserializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class LeesahConsumerTest {
    private val topic = "pdl.leesah-v1"
    private val partition = TopicPartition(topic, 0)

    // MockConsumer forbids reading committed offsets after close(), so we only record that close was called.
    private class TestConsumer : MockConsumer<String, GenericRecord>("earliest") {
        var wasClosed = false

        override fun close() {
            wasClosed = true
        }
    }

    private val mock = TestConsumer()
    private val handled = mutableListOf<Personhendelse>()
    private var handlerCalls = 0
    private val sleeps = mutableListOf<Duration>()
    private val health = ConsumerHealth()
    private val meterRegistry = SimpleMeterRegistry()

    private fun count(
        name: String,
        vararg tags: String,
    ) = meterRegistry
        .find(name)
        .tags(*tags)
        .counter()
        ?.count() ?: 0.0

    private fun leesahConsumer(
        handler: ForeldreansvarHendelseHandler =
            ForeldreansvarHendelseHandler {
                handlerCalls++
                handled += it
            },
    ) = LeesahConsumer(
        consumer = mock,
        topic = topic,
        filter = PersonhendelseFilter(setOf("FORELDREANSVAR_V1"), setOf("OPPHOERT", "ANNULLERT")),
        handler = handler,
        health = health,
        pollTimeout = Duration.ofMillis(1),
        sleep = { sleeps += it },
        meterRegistry = meterRegistry,
    )

    private fun record(
        offset: Long,
        value: GenericRecord?,
    ) = ConsumerRecord(topic, 0, offset, TEST_AKTOER_ID, value)

    /** Assigns the partition, delivers [records] in one poll, then stops the consumer on the next poll. */
    private fun runWith(
        consumer: LeesahConsumer,
        vararg records: ConsumerRecord<String, GenericRecord?>,
    ) {
        mock.schedulePollTask {
            mock.rebalance(listOf(partition))
            mock.updateBeginningOffsets(mapOf(partition to 0L))
            records.forEach { mock.addRecord(it) }
        }
        mock.schedulePollTask { consumer.stop() }
        consumer.run()
    }

    private fun committedOffset() = mock.committed(setOf(partition))[partition]?.offset()

    @Test
    fun `hands relevant events to the handler and commits the whole batch`() {
        runWith(
            leesahConsumer(),
            record(0, personhendelse(hendelseId = "a", endringstype = "OPPHOERT")),
            record(1, personhendelse(hendelseId = "b", opplysningstype = "DOEDSFALL_V1")),
            record(2, personhendelse(hendelseId = "c", endringstype = "OPPRETTET")),
            record(3, personhendelse(hendelseId = "d", endringstype = "ANNULLERT")),
            record(4, null),
        )

        assertEquals(listOf("a", "d"), handled.map { it.hendelseId })
        assertEquals(1, handlerCalls, "one call per poll")
        assertEquals(5L, committedOffset())
        assertEquals(5.0, count("leesah.events.read"))
        assertEquals(1.0, count("leesah.events.relevant", "endringstype", "OPPHOERT"))
        assertEquals(1.0, count("leesah.events.relevant", "endringstype", "ANNULLERT"))
        assertTrue(health.isAlive())
        assertTrue(mock.wasClosed)
    }

    @Test
    fun `retries with backoff and continues when the handler recovers`() {
        var calls = 0
        val consumer =
            leesahConsumer {
                calls++
                if (calls == 1) error("temporary")
                handled += it
            }

        runWith(consumer, record(0, personhendelse()))

        assertEquals(2, calls)
        assertEquals(listOf(Duration.ofSeconds(1)), sleeps)
        assertEquals(1L, committedOffset())
        assertTrue(health.isAlive())
    }

    @Test
    fun `stops without committing and reports not alive when the handler keeps failing`() {
        var calls = 0
        val consumer =
            leesahConsumer {
                calls++
                error("down")
            }

        runWith(consumer, record(0, personhendelse()))

        assertEquals(3, calls)
        assertEquals(3.0, count("leesah.handler.failures"))
        assertEquals(listOf(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps)
        assertNull(committedOffset())
        assertFalse(health.isAlive())
        assertTrue(mock.wasClosed)
    }

    @Test
    fun `skips a record that cannot be deserialized`() {
        val consumer = leesahConsumer()
        mock.schedulePollTask {
            mock.rebalance(listOf(partition))
            mock.updateBeginningOffsets(mapOf(partition to 0L))
            mock.setPollException(RecordDeserializationException(partition, 7, "bad bytes", null))
        }
        mock.schedulePollTask { consumer.stop() }

        consumer.run()

        assertEquals(8L, mock.position(partition))
        assertEquals(8L, committedOffset())
        assertEquals(1.0, count("leesah.records.skipped"))
        assertTrue(health.isAlive())
    }

    @Test
    fun `stop is a normal shutdown, not a failure`() {
        runWith(leesahConsumer())

        assertTrue(health.isAlive())
        assertTrue(mock.wasClosed)
    }

    @Test
    fun `does not commit and reports not alive when Salesforce keeps failing`() {
        val pdl =
            FakePdl { respondJson("""{"data":{"hentBarn":{"adressebeskyttelse":[],"foreldreansvar":[],"forelderBarnRelasjon":[]}}}""") }
        val salesforce = FakeSalesforce { respond("", HttpStatusCode.ServiceUnavailable) }

        runWith(leesahConsumer(AnsvarligeSync(pdl.client, salesforce.client)), record(0, personhendelse()))

        assertEquals(3, salesforce.apexRequests.size)
        assertEquals(3, salesforce.requests("send", "server_error"))
        assertEquals(listOf(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeps)
        assertNull(committedOffset())
        assertFalse(health.isAlive())
    }

    @Test
    fun `commits once Salesforce has accepted the poll`() {
        val pdl =
            FakePdl { respondJson("""{"data":{"hentBarn":{"adressebeskyttelse":[],"foreldreansvar":[],"forelderBarnRelasjon":[]}}}""") }
        val salesforce = FakeSalesforce()

        runWith(leesahConsumer(AnsvarligeSync(pdl.client, salesforce.client)), record(0, personhendelse()), record(1, personhendelse()))

        assertEquals(1, salesforce.apexRequests.size)
        assertEquals(2L, committedOffset())
        assertTrue(health.isAlive())
    }
}
