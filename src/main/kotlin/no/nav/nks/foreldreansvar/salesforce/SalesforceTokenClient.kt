package no.nav.nks.foreldreansvar.salesforce

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.Date

/** An access token and the Salesforce instance it is valid for, e.g. `https://nav--sit.sandbox.my.salesforce.com`. */
data class SalesforceToken(
    val accessToken: String,
    val instanceUrl: String,
) {
    override fun toString(): String = "SalesforceToken(accessToken=<masked>, instanceUrl=$instanceUrl)"
}

/**
 * Gets Salesforce access tokens with the OAuth 2.0 JWT bearer flow: we sign a short-lived JWT with our private key,
 * and Salesforce checks it against the certificate on the connected app. No password or client secret is involved.
 * See https://help.salesforce.com/s/articleView?id=sf.remoteaccess_oauth_jwt_flow.htm
 *
 * The token response has no expiry, so we keep the token until Salesforce rejects it ([token] with `forceNew`).
 * Only called from the Leesah consumer thread, so there is no locking.
 */
class SalesforceTokenClient(
    private val httpClient: HttpClient,
    private val config: SalesforceConfig,
    private val meterRegistry: MeterRegistry = SimpleMeterRegistry(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val algorithm = Algorithm.RSA256(null, parsePrivateKey(config.privateKeyPem))
    private var cached: SalesforceToken? = null

    suspend fun token(forceNew: Boolean = false): SalesforceToken {
        if (!forceNew) cached?.let { return it }
        val response =
            meterRegistry.timeSalesforce("token") {
                httpClient.submitForm(
                    url = "${config.loginUrl}/services/oauth2/token",
                    formParameters =
                        parameters {
                            append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                            append("assertion", assertion())
                        },
                )
            }
        if (!response.status.isSuccess()) {
            // The body may say why (e.g. invalid_grant), but we keep it out of logs to be safe.
            throw SalesforceException("Salesforce token endpoint returned HTTP ${response.status.value}")
        }
        val body = response.body<TokenResponse>()
        return SalesforceToken(body.accessToken, body.instanceUrl.trimEnd('/')).also { cached = it }
    }

    /** The signed JWT we trade for an access token. Salesforce allows at most 3 minutes until `exp`. */
    internal fun assertion(): String =
        JWT
            .create()
            .withIssuer(config.clientId)
            .withSubject(config.username)
            .withAudience(config.loginUrl)
            .withExpiresAt(Date.from(clock.instant().plus(Duration.ofMinutes(3))))
            .sign(algorithm)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
        @JsonProperty("instance_url") val instanceUrl: String,
    )

    companion object {
        /** Reads a PKCS#8 PEM key. Error messages never include the key itself. */
        fun parsePrivateKey(pem: String): RSAPrivateKey {
            val base64 =
                pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("\\n", "")
                    .filterNot { it.isWhitespace() }
            try {
                val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64))
                return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
            } catch (_: Exception) {
                throw IllegalArgumentException(
                    "SALESFORCE_PRIVATE_KEY is not a PKCS#8 RSA key in PEM format (-----BEGIN PRIVATE KEY-----)",
                )
            }
        }
    }
}
