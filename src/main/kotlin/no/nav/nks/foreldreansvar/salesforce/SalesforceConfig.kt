package no.nav.nks.foreldreansvar.salesforce

/**
 * Settings for closing henvendelser (`Thread__c`) in Salesforce through its standard REST API.
 *
 * `SALESFORCE_ENABLED`, `SALESFORCE_LUKK_ENABLED`, `SALESFORCE_LOGIN_URL`, `SALESFORCE_API_VERSION`,
 * `SALESFORCE_THREAD_TYPE` and `SALESFORCE_CLOSED_BY` are set in .nais/app.yaml.
 * `SALESFORCE_CLIENT_ID`, `SALESFORCE_USERNAME` and `SALESFORCE_PRIVATE_KEY` come from the Nais secret
 * `nks-foreldreansvar-salesforce`. See the Salesforce section in the README.
 */
data class SalesforceConfig(
    /** Where we ask for tokens, e.g. `https://test.salesforce.com` (sandboxes) or the org's My Domain URL. */
    val loginUrl: String,
    /** REST API version without the `v`, e.g. `62.0`. Used in `/services/data/v62.0/...`. */
    val apiVersion: String,
    /** The `CRM_Thread_Type__c` value of the threads we may close. Threads of other types are never touched. */
    val threadType: String,
    /**
     * `true`: really close threads. `false`: find them and log what we would close, but send no changes.
     * This is how we test in dev before we know what Salesforce automation runs when a thread is closed.
     */
    val lukkEnabled: Boolean,
    /** What we write to `CRM_Closed_By__c` on closed threads. Only needed when [lukkEnabled] is `true`. */
    val closedBy: String?,
    /** The connected app's consumer key. */
    val clientId: String,
    /** The integration user we act as. */
    val username: String,
    /** RSA private key as PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`). Its certificate is uploaded to the connected app. */
    val privateKeyPem: String,
) {
    init {
        require(!lukkEnabled || !closedBy.isNullOrBlank()) { "closedBy is required when lukkEnabled is true" }
    }

    // Keep secrets out of logs and error messages.
    override fun toString(): String =
        "SalesforceConfig(loginUrl=$loginUrl, apiVersion=$apiVersion, threadType=$threadType, lukkEnabled=$lukkEnabled)"

    companion object {
        private val API_VERSION_REGEX = Regex("^\\d+\\.\\d+$")

        /**
         * Returns null when `SALESFORCE_ENABLED` is `false` or not set: then we call Salesforce not at all and only log.
         * When it is `true`, every variable is required (`SALESFORCE_CLOSED_BY` only if `SALESFORCE_LUKK_ENABLED` is
         * `true`), so a missing secret stops the app instead of silently doing nothing.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): SalesforceConfig? {
            fun required(name: String): String =
                env[name]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: error("Missing environment variable $name. See the Salesforce section in the README.")

            fun flag(name: String): Boolean =
                when (val value = env[name]?.trim()?.lowercase()) {
                    null, "", "false" -> false
                    "true" -> true
                    else -> error("$name must be true or false, was '$value'.")
                }

            if (!flag("SALESFORCE_ENABLED")) return null
            val lukkEnabled = flag("SALESFORCE_LUKK_ENABLED")

            // Accept both "62.0" and "v62.0".
            val apiVersion = required("SALESFORCE_API_VERSION").removePrefix("v")
            check(API_VERSION_REGEX.matches(apiVersion)) { "SALESFORCE_API_VERSION must look like 62.0, was '$apiVersion'." }

            return SalesforceConfig(
                loginUrl = required("SALESFORCE_LOGIN_URL").trimEnd('/'),
                apiVersion = apiVersion,
                threadType = required("SALESFORCE_THREAD_TYPE"),
                lukkEnabled = lukkEnabled,
                closedBy = if (lukkEnabled) required("SALESFORCE_CLOSED_BY") else env["SALESFORCE_CLOSED_BY"]?.trim(),
                clientId = required("SALESFORCE_CLIENT_ID"),
                username = required("SALESFORCE_USERNAME"),
                privateKeyPem = required("SALESFORCE_PRIVATE_KEY"),
            )
        }
    }
}
