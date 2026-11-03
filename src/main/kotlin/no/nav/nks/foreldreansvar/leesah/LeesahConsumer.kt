package no.nav.nks.foreldreansvar.leesah

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.errors.RecordDeserializationException
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads `pdl.leesah-v1` and passes the relevant events from each poll to [handler] in one call.
 *
 * - Offsets are committed after the handler has handled the whole poll (at-least-once).
 * - If [handler] throws, we retry [maxAttempts] times with backoff. If it still fails, the consumer stops without
 *   committing and [health] reports not alive, so Nais restarts the pod and the batch is read again.
 * - A record we can't deserialize is logged (partition/offset only) and skipped.
 *
 * Metrics (no idents in names or tags): `leesah_events_read_total`, `leesah_events_relevant_total{endringstype}`,
 * `leesah_handler_failures_total`, `leesah_records_skipped_total`, and Kafka's own consumer metrics such as
 * `kafka_consumer_fetch_manager_records_lag_max` (consumer lag).
 *
 * KafkaConsumer is not thread-safe, so everything except [stop] runs on the consumer's own thread.
 */
class LeesahConsumer(
    private val consumer: Consumer<String, GenericRecord>,
    private val topic: String,
    private val filter: PersonhendelseFilter,
    private val handler: ForeldreansvarHendelseHandler,
    private val health: ConsumerHealth,
    private val maxAttempts: Int = 3,
    private val initialBackoff: Duration = Duration.ofSeconds(1),
    private val pollTimeout: Duration = Duration.ofSeconds(1),
    private val statsInterval: Duration = Duration.ofMinutes(5),
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
) {
    private val log = LoggerFactory.getLogger(LeesahConsumer::class.java)
    private val eventsRead = meterRegistry.counter("leesah.events.read")
    private val handlerFailures = meterRegistry.counter("leesah.handler.failures")
    private val recordsSkipped = meterRegistry.counter("leesah.records.skipped")

    // Reads the Kafka client's own metrics, including consumer lag per partition. Closed together with the consumer.
    private val kafkaMetrics = KafkaClientMetrics(consumer).also { it.bindTo(meterRegistry) }
    private val running = AtomicBoolean(true)
    private var thread: Thread? = null

    private var read = 0L
    private var matched = 0L
    private var lastStats: Instant = clock.instant()

    fun start() {
        thread = Thread(::run, "leesah-consumer").also { it.start() }
    }

    /** Safe to call from another thread. Waits for the consumer to finish its batch and close. */
    fun stop(timeout: Duration = Duration.ofSeconds(10)) {
        running.set(false)
        consumer.wakeup()
        thread?.join(timeout.toMillis())
    }

    fun run() {
        try {
            consumer.subscribe(listOf(topic))
            log.info("Leesah consumer started on topic {}", topic)
            while (running.get()) {
                val records = poll()
                health.polled()
                val hendelser = records.mapNotNull(::relevant)
                if (hendelser.isNotEmpty()) handleWithRetry(hendelser)
                if (!records.isEmpty) consumer.commitSync()
                logStats()
            }
        } catch (_: WakeupException) {
            // Thrown by poll/commit after stop() calls wakeup(); a normal shutdown.
        } catch (e: Exception) {
            log.error("Leesah consumer stopped after an error: {}", e::class.qualifiedName)
        } finally {
            // Still meant to be running means we stopped because of an error.
            if (running.get()) health.failed()
            kafkaMetrics.close()
            consumer.close()
            log.info("Leesah consumer closed")
        }
    }

    private fun poll(): ConsumerRecords<String, GenericRecord> =
        try {
            consumer.poll(pollTimeout)
        } catch (e: RecordDeserializationException) {
            // Everything before this record is already handled and committed, so it's safe to commit past it.
            val next = e.offset() + 1
            recordsSkipped.increment()
            log.warn("Skipping Leesah record we could not read: partition={}, offset={}", e.topicPartition(), e.offset())
            consumer.seek(e.topicPartition(), next)
            consumer.commitSync(mapOf(e.topicPartition() to OffsetAndMetadata(next)))
            ConsumerRecords.empty()
        }

    private fun relevant(record: ConsumerRecord<String, GenericRecord>): Personhendelse? {
        read++
        eventsRead.increment()
        // The key is an aktør-id, so it's never used or logged here.
        val value = record.value() ?: return null
        if (!filter.matches(value)) return null
        matched++
        val hendelse = Personhendelse.fra(value)
        meterRegistry.counter("leesah.events.relevant", "endringstype", hendelse.endringstype).increment()
        return hendelse
    }

    private fun handleWithRetry(hendelser: List<Personhendelse>) {
        var backoff = initialBackoff
        repeat(maxAttempts) { attempt ->
            try {
                handler.handle(hendelser)
                return
            } catch (e: Exception) {
                handlerFailures.increment()
                log.warn(
                    "Handling {} Leesah events failed (attempt {} of {}): error={}",
                    hendelser.size,
                    attempt + 1,
                    maxAttempts,
                    e::class.qualifiedName,
                )
                if (attempt + 1 == maxAttempts) throw e
                sleep(backoff)
                backoff = backoff.multipliedBy(2)
            }
        }
    }

    private fun logStats() {
        val now = clock.instant()
        if (Duration.between(lastStats, now) < statsInterval) return
        log.info("Leesah consumer: read {} events, {} relevant, since {}", read, matched, lastStats)
        read = 0
        matched = 0
        lastStats = now
    }

    companion object {
        fun create(
            config: LeesahConfig,
            handler: ForeldreansvarHendelseHandler,
            health: ConsumerHealth,
            meterRegistry: MeterRegistry,
        ): LeesahConsumer =
            LeesahConsumer(
                consumer = KafkaConsumer(config.consumerProperties()),
                topic = config.topic,
                filter = PersonhendelseFilter(config.opplysningstyper, config.endringstyper),
                handler = handler,
                health = health,
                meterRegistry = meterRegistry,
            )
    }
}
