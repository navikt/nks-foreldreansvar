package no.nav.nks.foreldreansvar.pdl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.isSuccess
import io.ktor.http.parameters

/**
 * Gets Entra ID machine-to-machine tokens from Texas, the token sidecar Nais runs next to the app.
 * Texas holds our client credentials and caches tokens, so we do not need a client secret or a cache here.
 * See https://doc.nais.io/auth/entra-id/how-to/consume-m2m/
 */
class TexasTokenClient(
    private val httpClient: HttpClient,
    private val tokenEndpoint: String,
) {
    /** [skipCache] forces a new token, e.g. after the target API has rejected the cached one. */
    suspend fun token(
        target: String,
        skipCache: Boolean = false,
    ): String {
        val response =
            httpClient.submitForm(
                url = tokenEndpoint,
                formParameters =
                    parameters {
                        append("identity_provider", "entra_id")
                        append("target", target)
                        if (skipCache) append("skip_cache", "true")
                    },
            )
        if (!response.status.isSuccess()) {
            throw PdlException.TokenUnavailable("Texas returned HTTP ${response.status.value} for target $target")
        }
        return response.body<TokenResponse>().accessToken
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
    )
}
