package no.nav.nks.foreldreansvar.leesah

import org.apache.avro.generic.GenericRecord
import java.time.Instant

/**
 * The few top-level fields we read from a Leesah `Personhendelse`.
 *
 * Schema: https://github.com/navikt/pdl/tree/master/libs/contract-pdl-avro/src/main/avro/no/nav/person/pdl/leesah
 * `personidenter` holds fnr/aktør-id, so [toString] masks it. Never log the list itself.
 */
data class Personhendelse(
    val hendelseId: String,
    val personidenter: List<String>,
    val opplysningstype: String,
    val endringstype: String,
    val tidligereHendelseId: String?,
    val opprettet: Instant,
) {
    override fun toString(): String =
        "Personhendelse(hendelseId=$hendelseId, personidenter=<${personidenter.size} masked>, " +
            "opplysningstype=$opplysningstype, endringstype=$endringstype, " +
            "tidligereHendelseId=$tidligereHendelseId, opprettet=$opprettet)"

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
            )
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
