package no.nav.nks.foreldreansvar.salesforce

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant

class SalesforceTokenClientTest {
    @Test
    fun `trades a signed JWT for a token with the JWT bearer grant`() =
        runBlocking {
            val salesforce = FakeSalesforce()

            val token = salesforce.tokenClient.token()

            assertEquals(SalesforceToken("token-1", TEST_INSTANCE_URL), token)
            val form = parseQueryString(salesforce.tokenRequests.single())
            assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])

            // Signed with our private key, so Salesforce can check it with the certificate on the connected app.
            val jwt =
                JWT
                    .require(Algorithm.RSA256(testKeyPair.public as RSAPublicKey, null))
                    .withIssuer("consumer-key")
                    .withSubject("integrasjon@nav.test")
                    .withAudience("https://login.salesforce.test")
                    .build()
                    .verify(form["assertion"])
            val expiresIn = Duration.between(Instant.now(), jwt.expiresAtAsInstant)
            assertTrue(expiresIn > Duration.ofMinutes(2) && expiresIn <= Duration.ofMinutes(3))
        }

    @Test
    fun `keeps the token until asked for a new one`() =
        runBlocking {
            val salesforce = FakeSalesforce()

            salesforce.tokenClient.token()
            val cached = salesforce.tokenClient.token()
            val fresh = salesforce.tokenClient.token(forceNew = true)

            assertEquals("token-1", cached.accessToken)
            assertEquals("token-2", fresh.accessToken)
            assertEquals(2, salesforce.tokenRequests.size)
        }

    @Test
    fun `throws without leaking details when Salesforce refuses the token request`() {
        val salesforce = FakeSalesforce(tokenStatus = HttpStatusCode.BadRequest)

        val e = assertThrows<SalesforceException> { runBlocking { salesforce.tokenClient.token() } }

        assertEquals("Salesforce token endpoint returned HTTP 400", e.message)
        assertEquals(1, salesforce.requests("token", "client_error"))
    }

    @Test
    fun `rejects a private key that is not PKCS#8 PEM`() {
        val e = assertThrows<IllegalArgumentException> { SalesforceTokenClient.parsePrivateKey("not a key") }
        assertTrue(e.message!!.contains("SALESFORCE_PRIVATE_KEY"))
    }
}
