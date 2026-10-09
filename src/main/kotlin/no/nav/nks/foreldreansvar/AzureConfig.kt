package no.nav.nks.foreldreansvar

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Entra ID (Azure AD) settings injected by Nais when `azure.application.enabled: true`.
 */
data class AzureConfig(
    val clientId: String,
    val issuer: String,
    val jwksUri: String,
    val preAuthorizedClientIds: Set<String>,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AzureConfig {
            fun required(name: String): String =
                env[name]?.takeIf { it.isNotBlank() }
                    ?: error("Missing environment variable $name. Nais injects it when azure.application is enabled.")

            return AzureConfig(
                clientId = required("AZURE_APP_CLIENT_ID"),
                issuer = required("AZURE_OPENID_CONFIG_ISSUER"),
                jwksUri = required("AZURE_OPENID_CONFIG_JWKS_URI"),
                preAuthorizedClientIds = parsePreAuthorizedApps(required("AZURE_APP_PRE_AUTHORIZED_APPS")),
            )
        }

        // Nais format: [{"name":"cluster:namespace:app","clientId":"..."}]
        private fun parsePreAuthorizedApps(json: String): Set<String> =
            jacksonObjectMapper()
                .readValue<List<PreAuthorizedApp>>(json)
                .map { it.clientId }
                .toSet()
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class PreAuthorizedApp(
        val name: String,
        val clientId: String,
    )
}
