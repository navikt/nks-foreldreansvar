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
const val TEST_ANSVARLIG_FNR = "10987654321"

private val foreldreansvarSchema: Schema =
    personhendelseSchema.getField("foreldreansvar").schema().types.single {
        it.type ==
            Schema.Type.RECORD
    }

fun foreldreansvar(
    ansvar: String = "felles",
    ansvarlig: String? = TEST_ANSVARLIG_FNR,
): GenericRecord = GenericRecordBuilder(foreldreansvarSchema).set("ansvar", ansvar).set("ansvarlig", ansvarlig).build()

fun personhendelse(
    hendelseId: String = "hendelse-1",
    opplysningstype: String = "FORELDREANSVAR_V1",
    endringstype: String = "OPPHOERT",
    tidligereHendelseId: String? = "hendelse-0",
    opprettetMillis: Long = 1_760_000_000_000,
    personidenter: List<String> = listOf(TEST_AKTOER_ID, TEST_FNR),
    foreldreansvar: GenericRecord? = foreldreansvar(),
): GenericRecord =
    GenericRecordBuilder(personhendelseSchema)
        .set("hendelseId", hendelseId)
        .set("personidenter", personidenter)
        .set("master", "FREG")
        .set("opprettet", opprettetMillis)
        .set("opplysningstype", opplysningstype)
        .set("endringstype", GenericData.EnumSymbol(personhendelseSchema.getField("endringstype").schema(), endringstype))
        .set("tidligereHendelseId", tidligereHendelseId)
        .set("foreldreansvar", foreldreansvar)
        .build()
