package no.nav.nks.foreldreansvar.salesforce

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SalesforceConfigTest {
    private val env =
        mapOf(
            "SALESFORCE_ENABLED" to "true",
            "SALESFORCE_LOGIN_URL" to "https://test.salesforce.com/",
            "SALESFORCE_ENDPOINT_PATH" to "/services/apexrest/foreldreansvar/v1",
            "SALESFORCE_CLIENT_ID" to "consumer-key",
            "SALESFORCE_USERNAME" to "integrasjon@nav.no",
            "SALESFORCE_PRIVATE_KEY" to "secret-key",
        )

    @Test
    fun `reads all variables when enabled`() {
        val config = SalesforceConfig.fromEnv(env)!!
        assertEquals("https://test.salesforce.com", config.loginUrl)
        assertEquals("/services/apexrest/foreldreansvar/v1", config.endpointPath)
        assertEquals("consumer-key", config.clientId)
        assertEquals("integrasjon@nav.no", config.username)
        assertEquals("secret-key", config.privateKeyPem)
    }

    @Test
    fun `is off when SALESFORCE_ENABLED is false or not set, even without the other variables`() {
        assertNull(SalesforceConfig.fromEnv(mapOf("SALESFORCE_ENABLED" to "false")))
        assertNull(SalesforceConfig.fromEnv(emptyMap()))
    }

    @Test
    fun `fails when enabled and a variable is missing`() {
        val e = assertThrows<IllegalStateException> { SalesforceConfig.fromEnv(env - "SALESFORCE_PRIVATE_KEY") }
        assertTrue(e.message!!.contains("SALESFORCE_PRIVATE_KEY"))
    }

    @Test
    fun `fails on an invalid SALESFORCE_ENABLED`() {
        assertThrows<IllegalStateException> { SalesforceConfig.fromEnv(env + ("SALESFORCE_ENABLED" to "ja")) }
    }

    @Test
    fun `toString does not show secrets`() {
        val text = SalesforceConfig.fromEnv(env).toString()
        assertFalse(text.contains("secret-key"))
        assertFalse(text.contains("consumer-key"))
        assertFalse(text.contains("integrasjon"))
    }
}
