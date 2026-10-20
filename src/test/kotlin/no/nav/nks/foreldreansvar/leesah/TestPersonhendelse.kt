package no.nav.nks.foreldreansvar.leesah

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.avro.generic.GenericRecordBuilder

val personhendelseSchema: Schema =
    Schema.Parser().parse(object {}.javaClass.getResourceAsStream("/leesah/personhendelse-test.avsc"))

// Synthetic test idents, not real people.
const val TEST_FNR = "12345678910"
const val TEST_AKTOER_ID = "1234567891011"

fun personhendelse(
    hendelseId: String = "hendelse-1",
    opplysningstype: String = "FORELDREANSVAR_V1",
    endringstype: String = "OPPHOERT",
    tidligereHendelseId: String? = "hendelse-0",
    opprettetMillis: Long = 1_760_000_000_000,
): GenericRecord =
    GenericRecordBuilder(personhendelseSchema)
        .set("hendelseId", hendelseId)
        .set("personidenter", listOf(TEST_FNR, TEST_AKTOER_ID))
        .set("master", "FREG")
        .set("opprettet", opprettetMillis)
        .set("opplysningstype", opplysningstype)
        .set("endringstype", GenericData.EnumSymbol(personhendelseSchema.getField("endringstype").schema(), endringstype))
        .set("tidligereHendelseId", tidligereHendelseId)
        .build()
