package no.nav.nks.foreldreansvar.pdl

/**
 * Settings for calling PDL. `NAIS_TOKEN_ENDPOINT` is injected by Nais (Texas); the rest are set in .nais/app.yaml.
 */
data class PdlConfig(
    val url: String,
    val target: String,
    val behandlingsnummer: String,
    val tokenEndpoint: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): PdlConfig {
            fun required(name: String): String =
                env[name]?.takeIf { it.isNotBlank() }
                    ?: error("Missing environment variable $name. See the PDL section in the README.")

            return PdlConfig(
                url = required("PDL_URL"),
                target = required("PDL_TARGET"),
                behandlingsnummer = required("PDL_BEHANDLINGSNUMMER"),
                tokenEndpoint = required("NAIS_TOKEN_ENDPOINT"),
            )
        }
    }
}
