package no.nav.nks.foreldreansvar.salesforce

/**
 * Settings for sending foreldreansvar to Salesforce.
 *
 * `SALESFORCE_ENABLED`, `SALESFORCE_LOGIN_URL` and `SALESFORCE_ENDPOINT_PATH` are set in .nais/app.yaml.
 * `SALESFORCE_CLIENT_ID`, `SALESFORCE_USERNAME` and `SALESFORCE_PRIVATE_KEY` come from the Nais secret
 * `nks-foreldreansvar-salesforce`. See the Salesforce section in the README.
 */
data class SalesforceConfig(
    /** Where we ask for tokens, e.g. `https://test.salesforce.com` (sandboxes) or the org's My Domain URL. */
    val loginUrl: String,
    /** Path of the Apex REST endpoint on the instance, e.g. `/services/apexrest/foreldreansvar/v1`. */
    val endpointPath: String,
    /** The connected app's consumer key. */
    val clientId: String,
    /** The integration user we act as. */
    val username: String,
    /** RSA private key as PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`). Its certificate is uploaded to the connected app. */
    val privateKeyPem: String,
) {
    // Keep secrets out of logs and error messages.
    override fun toString(): String = "SalesforceConfig(loginUrl=$loginUrl, endpointPath=$endpointPath)"

    companion object {
        /**
         * Returns null when `SALESFORCE_ENABLED` is `false` or not set: the pipeline then runs as a dry run and only logs.
         * When it is `true`, every variable is required, so a missing secret stops the app instead of silently not sending.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): SalesforceConfig? {
            fun required(name: String): String =
                env[name]?.takeIf { it.isNotBlank() }
                    ?: error("Missing environment variable $name. See the Salesforce section in the README.")

            when (val enabled = env["SALESFORCE_ENABLED"]?.trim()?.lowercase()) {
                null, "", "false" -> return null
                "true" -> Unit
                else -> error("SALESFORCE_ENABLED must be true or false, was '$enabled'.")
            }

            val endpointPath = required("SALESFORCE_ENDPOINT_PATH")
            check(endpointPath.startsWith("/")) { "SALESFORCE_ENDPOINT_PATH must start with /." }

            return SalesforceConfig(
                loginUrl = required("SALESFORCE_LOGIN_URL").trimEnd('/'),
                endpointPath = endpointPath,
                clientId = required("SALESFORCE_CLIENT_ID"),
                username = required("SALESFORCE_USERNAME"),
                privateKeyPem = required("SALESFORCE_PRIVATE_KEY"),
            )
        }
    }
}
