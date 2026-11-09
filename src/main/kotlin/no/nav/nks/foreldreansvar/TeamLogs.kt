package no.nav.nks.foreldreansvar

import org.slf4j.Marker
import org.slf4j.MarkerFactory

/**
 * Marks a log line that may contain idents. Marked lines only go to Nais Team Logs (private to teamnks), never to
 * stdout, in every environment: see logback.xml. Use it with any logger, e.g. `log.info(TEAM_LOGS, "... {}", ident)`.
 * Lines without the marker must stay free of idents.
 */
val TEAM_LOGS: Marker = MarkerFactory.getMarker("TEAM_LOGS")
