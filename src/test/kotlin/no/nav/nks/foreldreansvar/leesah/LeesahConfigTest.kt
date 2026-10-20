package no.nav.nks.foreldreansvar.leesah

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LeesahConfigTest {
    private val env =
        mapOf(
            "LEESAH_TOPIC" to "pdl.leesah-v1",
            "LEESAH_OPPLYSNINGSTYPER" to "FORELDREANSVAR_V1",
            "LEESAH_ENDRINGSTYPER" to "OPPHOERT, ANNULLERT",
            "KAFKA_BROKERS" to "broker:9092",
            "KAFKA_TRUSTSTORE_PATH" to "/var/run/secrets/truststore.jks",
            "KAFKA_KEYSTORE_PATH" to "/var/run/secrets/keystore.p12",
            "KAFKA_CREDSTORE_PASSWORD" to "secret-password",
            "KAFKA_SCHEMA_REGISTRY" to "https://registry",
            "KAFKA_SCHEMA_REGISTRY_USER" to "user",
            "KAFKA_SCHEMA_REGISTRY_PASSWORD" to "registry-password",
        )

    @Test
    fun `reads values and splits lists`() {
        val config = LeesahConfig.fromEnv(env)!!

        assertEquals("pdl.leesah-v1", config.topic)
        assertEquals(setOf("FORELDREANSVAR_V1"), config.opplysningstyper)
        assertEquals(setOf("OPPHOERT", "ANNULLERT"), config.endringstyper)
    }

    @Test
    fun `enabled by default and disabled with LEESAH_ENABLED=false`() {
        assertEquals(LeesahConfig.fromEnv(env), LeesahConfig.fromEnv(env + ("LEESAH_ENABLED" to "true")))
        assertNull(LeesahConfig.fromEnv(mapOf("LEESAH_ENABLED" to "false")))
    }

    @Test
    fun `rejects invalid LEESAH_ENABLED`() {
        assertThrows<IllegalStateException> { LeesahConfig.fromEnv(env + ("LEESAH_ENABLED" to "yes")) }
    }

    @Test
    fun `fails with a clear message when a variable is missing`() {
        val error = assertThrows<IllegalStateException> { LeesahConfig.fromEnv(env - "KAFKA_BROKERS") }

        assertTrue(error.message!!.contains("KAFKA_BROKERS"))
    }

    @Test
    fun `rejects unknown endringstyper`() {
        val error = assertThrows<IllegalStateException> { LeesahConfig.fromEnv(env + ("LEESAH_ENDRINGSTYPER" to "OPPHORT")) }

        assertTrue(error.message!!.contains("OPPHORT"))
    }

    @Test
    fun `commits manually and reads generic records`() {
        val props = LeesahConfig.fromEnv(env)!!.consumerProperties()

        assertEquals(false, props[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG])
        assertEquals("nks-foreldreansvar-leesah-v1", props[ConsumerConfig.GROUP_ID_CONFIG])
        assertEquals(false, props["specific.avro.reader"])
    }

    @Test
    fun `toString hides secrets`() {
        val text = LeesahConfig.fromEnv(env).toString()

        assertFalse(text.contains("secret-password"))
        assertFalse(text.contains("registry-password"))
    }
}
