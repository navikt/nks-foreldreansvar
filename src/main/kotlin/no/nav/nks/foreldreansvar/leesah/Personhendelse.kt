package no.nav.nks.foreldreansvar.leesah

import org.apache.avro.generic.GenericRecord
import java.time.Instant

/**
 * The few top-level fields we read from a Leesah `Personhendelse`.
 *
 * Schema: https://github.com/navikt/pdl/tree/master/libs/contract-pdl-avro/src/main/avro/no/nav/person/pdl/leesah
 * `personidenter` holds fnr/aktør-id, so [toString] masks it. Never log the list itself.
 * For FORELDREANSVAR_V1 the person is the child, and [foreldreansvar] says who has the responsibility.
 */
data class Personhendelse(
    val hendelseId: String,
    val personidenter: List<String>,
    val opplysningstype: String,
    val endringstype: String,
    val tidligereHendelseId: String?,
    val opprettet: Instant,
    val foreldreansvar: Foreldreansvar? = null,
) {
    /** The person's fnr/dnr (11 digits). `personidenter` also holds the 13-digit aktør-id, which we skip. */
    val fnr: String?
        get() = personidenter.firstOrNull { ident -> ident.length == 11 && ident.all { it.isDigit() } }

    override fun toString(): String =
        "Personhendelse(hendelseId=$hendelseId, personidenter=<${personidenter.size} masked>, " +
            "opplysningstype=$opplysningstype, endringstype=$endringstype, " +
            "tidligereHendelseId=$tidligereHendelseId, opprettet=$opprettet, foreldreansvar=$foreldreansvar)"

    companion object {
        // Avro gives strings as Utf8 and enums as EnumSymbol, so we convert with toString().
        fun opplysningstype(record: GenericRecord): String = record.get("opplysningstype").toString()

        fun endringstype(record: GenericRecord): String = record.get("endringstype").toString()

        fun fra(record: GenericRecord): Personhendelse =
            Personhendelse(
                hendelseId = record.get("hendelseId").toString(),
                personidenter = (record.get("personidenter") as Collection<*>).map { it.toString() },
                opplysningstype = opplysningstype(record),
                endringstype = endringstype(record),
                tidligereHendelseId = record.get("tidligereHendelseId")?.toString(),
                opprettet =
                    when (val opprettet = record.get("opprettet")) {
                        is Instant -> opprettet
                        is Long -> Instant.ofEpochMilli(opprettet)
                        else -> error("Unexpected type for opprettet: ${opprettet?.javaClass?.name}")
                    },
                foreldreansvar = Foreldreansvar.fra(record),
            )
    }
}

/**
 * The `foreldreansvar` field on a Leesah event: `ansvar` is e.g. "felles", "mor", "far" or "andre",
 * and `ansvarlig` is the fnr of the responsible person when PDL knows it. [toString] masks `ansvarlig`.
 */
data class Foreldreansvar(
    val ansvar: String,
    val ansvarlig: String?,
) {
    override fun toString(): String = "Foreldreansvar(ansvar=$ansvar, ansvarlig=${if (ansvarlig == null) "null" else "<masked>"})"

    companion object {
        /**
         * Returns null if the field is empty, or missing because the record was written with an older schema.
         * The field is new in PDL's schema, so we read it defensively instead of assuming it's there.
         */
        fun fra(record: GenericRecord): Foreldreansvar? {
            if (record.schema.getField("foreldreansvar") == null) return null
            val foreldreansvar = record.get("foreldreansvar") as? GenericRecord ?: return null
            return Foreldreansvar(
                ansvar = foreldreansvar.get("ansvar").toString(),
                ansvarlig = foreldreansvar.get("ansvarlig")?.toString(),
            )
        }
    }
}

/** Decides which Leesah events we care about. Values come from LEESAH_OPPLYSNINGSTYPER and LEESAH_ENDRINGSTYPER. */
class PersonhendelseFilter(
    private val opplysningstyper: Set<String>,
    private val endringstyper: Set<String>,
) {
    fun matches(record: GenericRecord): Boolean =
        Personhendelse.opplysningstype(record) in opplysningstyper &&
            Personhendelse.endringstype(record) in endringstyper
}
