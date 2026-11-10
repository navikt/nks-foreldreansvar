package no.nav.nks.foreldreansvar

import no.nav.nks.foreldreansvar.pdl.Foedselsdato
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeParseException

// The age rule for foreldreansvar, shared by barn, verifiser and ansvarlige (endpoint and Leesah sync).
// Foreldreansvar ends when the child turns 18 (myndig), even if PDL still lists it.

const val MYNDIGHETSALDER = 18

private val OSLO: ZoneId = ZoneId.of("Europe/Oslo")

/** Today's date in Norway, whatever zone [clock] has. Age is decided by the Norwegian date. */
fun idag(clock: Clock): LocalDate = LocalDate.now(clock.withZone(OSLO))

/** Whole years from [foedselsdato] (`yyyy-MM-dd`) to [today], or `null` if missing, unparseable or in the future. */
fun alder(
    foedselsdato: String?,
    today: LocalDate,
): Int? {
    val dato =
        try {
            foedselsdato?.let(LocalDate::parse)
        } catch (_: DateTimeParseException) {
            null
        } ?: return null
    return Period.between(dato, today).years.takeIf { it >= 0 }
}

/**
 * True if the child must be treated as myndig (18 or older), so no one has foreldreansvar we may act on. Fail closed:
 * - A full `foedselsdato` decides. The 18th birthday counts as myndig on that day.
 * - Otherwise `foedselsaar` is used only when the answer is certain: `today.year - foedselsaar` <= 17 is under 18,
 *   >= 19 is myndig. Exactly 18 could go either way, so it counts as myndig.
 * - No usable birth data counts as myndig.
 *
 * Turning 18 is not a PDL event, so the Leesah sync never removes sharing for a child who just turned 18. Salesforce
 * must handle that itself, e.g. with a scheduled job or by calling `/ansvarlige` for children who turn 18.
 */
fun List<Foedselsdato>.regnesSomMyndig(today: LocalDate): Boolean {
    firstNotNullOfOrNull { alder(it.foedselsdato, today) }?.let { return it >= MYNDIGHETSALDER }
    val aar = firstNotNullOfOrNull { it.foedselsaar } ?: return true
    return today.year - aar >= MYNDIGHETSALDER
}
