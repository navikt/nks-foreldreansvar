package no.nav.nks.foreldreansvar.leesah

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.Properties

/**
 * Settings for reading `pdl.leesah-v1`.
 *
 * The `KAFKA_*` variables are injected by Nais when `kafka.pool` is set; the `LEESAH_*` variables are set in .nais/app.yaml.
 */
data class LeesahConfig(
    val topic: String,
    val opplysningstyper: Set<String>,
    val endringstyper: Set<String>,
    val brokers: String,
    val truststorePath: String,
    val keystorePath: String,
    val credstorePassword: String,
    val schemaRegistryUrl: String,
    val schemaRegistryUser: String,
    val schemaRegistryPassword: String,
) {
    fun consumerProperties(): Properties =
        Properties().apply {
            put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, brokers)
            put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
            put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "")
            put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "JKS")
            put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, truststorePath)
            put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, credstorePassword)
            put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PKCS12")
            put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, keystorePath)
            put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, credstorePassword)
            put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, credstorePassword)

            // Changing the group id makes Kafka treat us as a new consumer, so we would start over from auto.offset.reset.
            put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID)
            // Only used the first time the group starts. After that we continue from our committed offsets.
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
            // We commit ourselves after a batch is handled, so a crash never skips unhandled events.
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100)

            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer::class.java)
            put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl)
            put(AbstractKafkaSchemaSerDeConfig.BASIC_AUTH_CREDENTIALS_SOURCE, "USER_INFO")
            put(AbstractKafkaSchemaSerDeConfig.USER_INFO_CONFIG, "$schemaRegistryUser:$schemaRegistryPassword")
            // GenericRecord instead of generated classes: we only read a few top-level fields.
            put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, false)
        }

    // Keep secrets out of logs and error messages.
    override fun toString(): String =
        "LeesahConfig(topic=$topic, opplysningstyper=$opplysningstyper, endringstyper=$endringstyper, brokers=$brokers)"

    companion object {
        const val GROUP_ID = "nks-foreldreansvar-leesah-v1"

        private val gyldigeEndringstyper = setOf("OPPRETTET", "KORRIGERT", "ANNULLERT", "OPPHOERT")

        /** Returns null when `LEESAH_ENABLED=false`, so the app can run without the consumer. */
        fun fromEnv(env: Map<String, String> = System.getenv()): LeesahConfig? {
            fun required(name: String): String =
                env[name]?.takeIf { it.isNotBlank() }
                    ?: error("Missing environment variable $name. See the Leesah section in the README.")

            fun requiredList(name: String): Set<String> =
                required(name).split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet().ifEmpty {
                    error("Environment variable $name must list at least one value.")
                }

            when (val enabled = env["LEESAH_ENABLED"]?.trim()?.lowercase()) {
                null, "", "true" -> Unit
                "false" -> return null
                else -> error("LEESAH_ENABLED must be true or false, was '$enabled'.")
            }

            val endringstyper = requiredList("LEESAH_ENDRINGSTYPER")
            val ukjente = endringstyper - gyldigeEndringstyper
            check(ukjente.isEmpty()) { "Unknown LEESAH_ENDRINGSTYPER $ukjente. Valid values: $gyldigeEndringstyper." }

            return LeesahConfig(
                topic = required("LEESAH_TOPIC"),
                opplysningstyper = requiredList("LEESAH_OPPLYSNINGSTYPER"),
                endringstyper = endringstyper,
                brokers = required("KAFKA_BROKERS"),
                truststorePath = required("KAFKA_TRUSTSTORE_PATH"),
                keystorePath = required("KAFKA_KEYSTORE_PATH"),
                credstorePassword = required("KAFKA_CREDSTORE_PASSWORD"),
                schemaRegistryUrl = required("KAFKA_SCHEMA_REGISTRY"),
                schemaRegistryUser = required("KAFKA_SCHEMA_REGISTRY_USER"),
                schemaRegistryPassword = required("KAFKA_SCHEMA_REGISTRY_PASSWORD"),
            )
        }
    }
}
