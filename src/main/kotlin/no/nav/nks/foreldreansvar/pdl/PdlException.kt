package no.nav.nks.foreldreansvar.pdl

/**
 * Errors from calling PDL. Messages must never contain idents or response data, since they may end up in logs.
 */
sealed class PdlException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** The person does not exist in PDL. */
    class NotFound : PdlException("Person not found in PDL")

    /** PDL rejected our token or we lack access (e.g. wrong behandlingsnummer). */
    class Unauthorized(
        message: String,
    ) : PdlException(message)

    /** PDL says the request is invalid (e.g. malformed ident). */
    class BadRequest(
        message: String,
    ) : PdlException(message)

    /** Could not get a token from Texas. */
    class TokenUnavailable(
        message: String,
    ) : PdlException(message)

    /** PDL did not answer in time. */
    class Timeout(
        cause: Throwable,
    ) : PdlException("Timed out calling PDL", cause)

    /** Any other error: HTTP 5xx, unknown GraphQL error codes or unexpected responses. */
    class Unexpected(
        message: String,
        cause: Throwable? = null,
    ) : PdlException(message, cause)
}
