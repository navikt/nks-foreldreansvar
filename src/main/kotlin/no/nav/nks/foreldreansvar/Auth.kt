package no.nav.nks.foreldreansvar

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.TimeUnit

const val AZURE_AUTH = "azure"

private const val M2M_ROLE = "access_as_application"
private val log = LoggerFactory.getLogger("no.nav.nks.foreldreansvar.Auth")

/**
 * Validates Entra ID access tokens for machine-to-machine calls (e.g. from saas-proxy on behalf of Salesforce).
 *
 * The verifier checks signature (JWKS), issuer, audience and expiry.
 * [isAllowedM2mToken] then checks that the token is an app token from a pre-authorized client.
 */
fun Application.configureAuth(config: AzureConfig) {
    val jwkProvider =
        JwkProviderBuilder(URI(config.jwksUri).toURL())
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()

    install(Authentication) {
        jwt(AZURE_AUTH) {
            verifier(jwkProvider, config.issuer) {
                withAudience(config.clientId)
            }
            validate { credential ->
                val payload = credential.payload
                val idtyp = payload.getClaim("idtyp").asString()
                val roles = payload.getClaim("roles").asList(String::class.java).orEmpty()
                val azp = payload.getClaim("azp").asString()

                if (isAllowedM2mToken(idtyp, roles, azp, config.preAuthorizedClientIds)) {
                    JWTPrincipal(payload)
                } else {
                    log.info("Rejected token: idtyp={}, hasM2mRole={}, azp={}", idtyp, M2M_ROLE in roles, azp)
                    null
                }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized)
            }
        }
    }
}

internal fun isAllowedM2mToken(
    idtyp: String?,
    roles: List<String>,
    azp: String?,
    preAuthorizedClientIds: Set<String>,
): Boolean = idtyp == "app" && M2M_ROLE in roles && azp != null && azp in preAuthorizedClientIds
