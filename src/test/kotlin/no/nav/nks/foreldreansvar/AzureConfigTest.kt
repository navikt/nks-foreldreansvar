package no.nav.nks.foreldreansvar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AzureConfigTest {
    private val env =
        mapOf(
            "AZURE_APP_CLIENT_ID" to "our-client-id",
            "AZURE_OPENID_CONFIG_ISSUER" to "https://issuer.example",
            "AZURE_OPENID_CONFIG_JWKS_URI" to "https://issuer.example/jwks",
            "AZURE_APP_PRE_AUTHORIZED_APPS" to
                """[{"name":"dev-gcp:platforce:saas-proxy","clientId":"saas-proxy-client-id"},{"name":"x:y:z","clientId":"other"}]""",
        )

    @Test
    fun `reads all values from env`() {
        val config = AzureConfig.fromEnv(env)

        assertEquals("our-client-id", config.clientId)
        assertEquals("https://issuer.example", config.issuer)
        assertEquals("https://issuer.example/jwks", config.jwksUri)
        assertEquals(setOf("saas-proxy-client-id", "other"), config.preAuthorizedClientIds)
    }

    @Test
    fun `fails with a clear message when a variable is missing`() {
        val error = assertThrows<IllegalStateException> { AzureConfig.fromEnv(env - "AZURE_APP_CLIENT_ID") }

        assertTrue(error.message!!.contains("AZURE_APP_CLIENT_ID"))
    }
}
