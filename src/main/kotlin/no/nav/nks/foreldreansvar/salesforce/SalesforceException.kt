package no.nav.nks.foreldreansvar.salesforce

/** Errors from calling Salesforce. Messages must never contain idents or response bodies, since they end up in logs. */
class SalesforceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
