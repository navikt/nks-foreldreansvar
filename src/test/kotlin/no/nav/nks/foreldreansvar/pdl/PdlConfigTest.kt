package no.nav.nks.foreldreansvar.pdl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PdlConfigTest {
    private val env =
        mapOf(
            "PDL_URL" to "https://pdl.example/graphql",
            "PDL_TARGET" to "dev-fss:pdl:pdl-api",
            "PDL_BEHANDLINGSNUMMER" to "B530",
            "NAIS_TOKEN_ENDPOINT" to "http://texas/api/v1/token",
        )

    @Test
    fun `reads all values from env`() {
        assertEquals(
            PdlConfig(
                url = "https://pdl.example/graphql",
                target = "dev-fss:pdl:pdl-api",
                behandlingsnummer = "B530",
                tokenEndpoint = "http://texas/api/v1/token",
            ),
            PdlConfig.fromEnv(env),
        )
    }

    @Test
    fun `fails with a clear message when a variable is missing`() {
        val error = assertThrows<IllegalStateException> { PdlConfig.fromEnv(env - "NAIS_TOKEN_ENDPOINT") }

        assertTrue(error.message!!.contains("NAIS_TOKEN_ENDPOINT"))
    }
}
