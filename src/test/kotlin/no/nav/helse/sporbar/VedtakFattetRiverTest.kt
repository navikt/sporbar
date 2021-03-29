package no.nav.helse.sporbar

import com.fasterxml.jackson.databind.JsonNode
import io.mockk.clearAllMocks
import io.mockk.mockk
import io.mockk.verify
import no.nav.helse.rapids_rivers.asLocalDate
import no.nav.helse.rapids_rivers.testsupport.TestRapid
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class VedtakFattetRiverTest {

    companion object {
        val FØDSELSNUMMER = "12345678910"
        val ORGNUMMER = "123456789"
        val TIDSSTEMPEL = LocalDateTime.now()
        val FOM = LocalDate.of(2020, 1, 1)
        val TOM = LocalDate.of(2020, 1, 31)
        val SKJÆRINGSTIDSPUNKT = LocalDate.of(2020, 1, 1)
        val SYKEPENGEGRUNNLAG = 388260.0
        val INNTEKT = 388260.0
        val AKTØRID = "123"
    }

    private val testRapid = TestRapid()
    private val producerMock = mockk<KafkaProducer<String,JsonNode>>(relaxed = true)
    private val dataSource = setUpDatasourceWithFlyway()
    private val dokumentDao = DokumentDao(dataSource)

    private val vedtaksperiodeDao = VedtaksperiodeDao(dataSource)
    private val vedtakDao = VedtakDao(dataSource)
    private val vedtaksperiodeMediator = VedtaksperiodeMediator(
        vedtaksperiodeDao = vedtaksperiodeDao,
        vedtakDao = vedtakDao,
        dokumentDao = dokumentDao,
        producer = producerMock
    )

    private val vedtakFattetMediator = VedtakFattetMediator(
        dokumentDao = dokumentDao,
        producer = producerMock
    )
    private val utbetalingMediator = UtbetalingMediator(
        producer = producerMock
    )

    init {
        NyttDokumentRiver(testRapid, dokumentDao)
        VedtaksperiodeEndretRiver(testRapid, vedtaksperiodeMediator)
        VedtakFattetRiver(testRapid, vedtakFattetMediator)
        UtbetalingUtbetaltRiver(testRapid, utbetalingMediator)
    }

    @AfterAll
    fun cleanUp() {
        dataSource.close()
    }

    @BeforeEach
    fun setup() {
        testRapid.reset()
        clearAllMocks()
    }

    @Test
    fun `vedtakFattet uten utbetaling`() {
        val captureSlot = mutableListOf<ProducerRecord<String, JsonNode>>()
        val idSett = IdSett()

        sykmeldingSendt(idSett)
        søknadSendt(idSett)
        inntektsmeldingSendt(idSett)
        vedtakFattetUtenUtbetalingSendt(idSett)

        verify { producerMock.send( capture(captureSlot) ) }

        val vedtakFattet = captureSlot.last()
        assertEquals(FØDSELSNUMMER, vedtakFattet.key())

        val vedtakFattetJson = vedtakFattet.value()
        assertEquals(vedtakFattetJson["fødselsnummer"].textValue(), FØDSELSNUMMER)
        assertEquals(vedtakFattetJson["aktørId"].textValue(), AKTØRID)
        assertEquals(vedtakFattetJson["fom"].asLocalDate(), FOM)
        assertEquals(vedtakFattetJson["tom"].asLocalDate(), TOM)
        assertEquals(vedtakFattetJson["skjæringstidspunkt"].asLocalDate(), SKJÆRINGSTIDSPUNKT)
        assertEquals(vedtakFattetJson["inntekt"].asDouble(), INNTEKT)
        assertEquals(vedtakFattetJson["sykepengegrunnlag"].asDouble(), SYKEPENGEGRUNNLAG)
        assertTrue(vedtakFattetJson.path("utbetalingId").isNull)
        assertTrue(vedtakFattetJson.path("vedtaksperiodeId").isMissingNode)

        assertTrue(vedtakFattetJson["dokumenter"].map { UUID.fromString(it["dokumentId"].asText()) }
            .contains(idSett.søknadDokumentId))
    }

    @Test
    fun `vedtakFattet med utbetaling`() {
        val captureSlot = mutableListOf<ProducerRecord<String, JsonNode>>()
        val idSett = IdSett()

        sykmeldingSendt(idSett)
        søknadSendt(idSett)
        inntektsmeldingSendt(idSett)
        vedtakFattetMedUtbetalingSendt(idSett)

        verify { producerMock.send( capture(captureSlot) ) }

        val vedtakFattet = captureSlot.last()
        assertEquals(FØDSELSNUMMER, vedtakFattet.key())

        val vedtakFattetJson = vedtakFattet.value()
        assertEquals(vedtakFattetJson["fødselsnummer"].textValue(), FØDSELSNUMMER)
        assertEquals(vedtakFattetJson["fom"].asLocalDate(), FOM)
        assertEquals(vedtakFattetJson["tom"].asLocalDate(), TOM)
        assertEquals(vedtakFattetJson["skjæringstidspunkt"].asLocalDate(), SKJÆRINGSTIDSPUNKT)
        assertEquals(vedtakFattetJson["utbetalingId"].let { UUID.fromString(it.asText())}, idSett.utbetalingId)

        assertTrue(vedtakFattetJson["dokumenter"].map { UUID.fromString(it["dokumentId"].asText()) }
            .contains(idSett.søknadDokumentId))
    }

    private fun sykmeldingSendt(
        idSett: IdSett,
        hendelseIder: List<UUID> = listOf(idSett.nySøknadHendelseId)
    ) {
        testRapid.sendTestMessage(
            nySøknadMessage(
                nySøknadHendelseId = idSett.nySøknadHendelseId,
                søknadDokumentId = idSett.søknadDokumentId,
                sykmeldingDokumentId = idSett.sykmeldingDokumentId
            )
        )
        testRapid.sendTestMessage(
            vedtaksperiodeEndret(
                "START",
                "MOTTATT_SYKMELDING_FERDIG_GAP",
                idSett.vedtaksperiodeId,
                hendelseIder
            )
        )
    }

    private fun søknadSendt(
        idSett: IdSett,
        hendelseIder: List<UUID> = listOf(idSett.nySøknadHendelseId, idSett.sendtSøknadHendelseId)
    ) {
        testRapid.sendTestMessage(
            sendtSøknadMessage(
                sendtSøknadHendelseId = idSett.sendtSøknadHendelseId,
                søknadDokumentId = idSett.søknadDokumentId,
                sykmeldingDokumentId = idSett.sykmeldingDokumentId
            )
        )
        testRapid.sendTestMessage(
            vedtaksperiodeEndret(
                "MOTTATT_SYKMELDING_FERDIG_GAP",
                "AVVENTER_INNTEKTSMELDING_ELLER_HISTORIKK_FERDIG_GAP",
                idSett.vedtaksperiodeId,
                hendelseIder
            )
        )
    }

    private fun inntektsmeldingSendt(
        idSett: IdSett,
        hendelseIder: List<UUID> = listOf(idSett.nySøknadHendelseId, idSett.inntektsmeldingHendelseId)
    ) {
        testRapid.sendTestMessage(
            inntektsmeldingMessage(
                inntektsmeldingHendelseId = idSett.inntektsmeldingHendelseId,
                inntektsmeldingDokumentId = idSett.inntektsmeldingDokumentId
            )
        )
        testRapid.sendTestMessage(
            vedtaksperiodeEndret(
                "AVVENTER_INNTEKTSMELDING_ELLER_HISTORIKK_FERDIG_GAP",
                "AVVENTER_HISTORIKK",
                idSett.vedtaksperiodeId,
                hendelseIder
            )
        )
    }

    private fun vedtakFattetMedUtbetalingSendt(
        idSett: IdSett,
        hendelseIder: List<UUID> = listOf(
            idSett.nySøknadHendelseId,
            idSett.sendtSøknadHendelseId,
            idSett.inntektsmeldingHendelseId
    )) {
        testRapid.sendTestMessage(
            vedtaksperiodeEndret(
                "TIL UTBETALING",
                "AVSLUTTET",
                idSett.vedtaksperiodeId,
                hendelseIder
            )
        )
        testRapid.sendTestMessage(vedtakFattetMedUtbetaling(idSett))
    }

    private fun vedtakFattetUtenUtbetalingSendt(
        idSett: IdSett,
        hendelseIder: List<UUID> = listOf(
            idSett.nySøknadHendelseId,
            idSett.sendtSøknadHendelseId,
            idSett.inntektsmeldingHendelseId
    )) {
        testRapid.sendTestMessage(
            vedtaksperiodeEndret(
                "TIL UTBETALING",
                "AVSLUTTET",
                idSett.vedtaksperiodeId,
                hendelseIder
            )
        )
        testRapid.sendTestMessage(vedtakFattetUtenUtbetaling(idSett))
    }

    @Language("json")
    private fun vedtakFattetUtenUtbetaling(
        idSett: IdSett,
        hendelser: List<UUID> = listOf(
            idSett.nySøknadHendelseId,
            idSett.sendtSøknadHendelseId,
            idSett.inntektsmeldingHendelseId
    )) = """{
  "vedtaksperiodeId": "$idSett.vedtaksperiodeId",
  "fom": "$FOM",
  "tom": "$TOM",
  "skjæringstidspunkt": "$SKJÆRINGSTIDSPUNKT",
  "hendelser": ${hendelser.map { "\"${it}\"" }},
  "sykepengegrunnlag": "$SYKEPENGEGRUNNLAG",
  "inntekt": "$INNTEKT",
  "@event_name": "vedtak_fattet",
  "@id": "1826ead5-4e9e-4670-892d-ea4ec2ffec04",
  "@opprettet": "$TIDSSTEMPEL",
  "aktørId": "$AKTØRID",
  "fødselsnummer": "$FØDSELSNUMMER",
  "organisasjonsnummer": "$ORGNUMMER"
}
    """

    @Language("json")
    private fun vedtakFattetMedUtbetaling(
        idSett: IdSett,
        hendelser: List<UUID> = listOf(
            idSett.nySøknadHendelseId,
            idSett.sendtSøknadHendelseId,
            idSett.inntektsmeldingHendelseId
        ),
        vedtaksperiodeId: UUID = idSett.vedtaksperiodeId,
        utbetalingId: UUID = idSett.utbetalingId) = """{
  "vedtaksperiodeId": "$vedtaksperiodeId",
  "fom": "$FOM",
  "tom": "$TOM",
  "skjæringstidspunkt": "$SKJÆRINGSTIDSPUNKT",
  "hendelser": ${hendelser.map { "\"${it}\"" }},
  "sykepengegrunnlag": "$SYKEPENGEGRUNNLAG",
  "inntekt": "$INNTEKT",
  "utbetalingId": "$utbetalingId",
  "@event_name": "vedtak_fattet",
  "@id": "1826ead5-4e9e-4670-892d-ea4ec2ffec04",
  "@opprettet": "$TIDSSTEMPEL",
  "aktørId": "$AKTØRID",
  "fødselsnummer": "$FØDSELSNUMMER",
  "organisasjonsnummer": "$ORGNUMMER"
}
    """

    @Language("JSON")
    private fun nySøknadMessage(
        nySøknadHendelseId: UUID,
        sykmeldingDokumentId: UUID,
        søknadDokumentId: UUID
    ) =
        """{
            "@event_name": "ny_søknad",
            "@id": "$nySøknadHendelseId",
            "id": "$søknadDokumentId",
            "sykmeldingId": "$sykmeldingDokumentId",
            "@opprettet": "2020-06-10T10:46:46.007854"
        }"""

    @Language("JSON")
    private fun sendtSøknadMessage(
        sendtSøknadHendelseId: UUID,
        sykmeldingDokumentId: UUID,
        søknadDokumentId: UUID
    ) =
        """{
            "@event_name": "sendt_søknad_nav",
            "@id": "$sendtSøknadHendelseId",
            "id": "$søknadDokumentId",
            "sykmeldingId": "$sykmeldingDokumentId",
            "@opprettet": "2020-06-11T10:46:46.007854"
        }"""

    @Language("JSON")
    private fun inntektsmeldingMessage(
        inntektsmeldingHendelseId: UUID,
        inntektsmeldingDokumentId: UUID
    ) =
        """{
            "@event_name": "inntektsmelding",
            "@id": "$inntektsmeldingHendelseId",
            "inntektsmeldingId": "$inntektsmeldingDokumentId",
            "@opprettet": "2020-06-11T10:46:46.007854"
        }"""

    @Language("JSON")
    private fun vedtaksperiodeEndret(
        forrige: String,
        gjeldendeTilstand: String,
        vedtaksperiodeId: UUID,
        hendelser: List<UUID>
    ) = """{
    "vedtaksperiodeId": "$vedtaksperiodeId",
    "organisasjonsnummer": "$ORGNUMMER",
    "gjeldendeTilstand": "$gjeldendeTilstand",
    "forrigeTilstand": "$forrige",
    "aktivitetslogg": {
        "aktiviteter": []
    },
    "vedtaksperiode_aktivitetslogg": {
        "aktiviteter": [],
        "kontekster": []
    },
    "hendelser": ${hendelser.map { "\"${it}\"" }},
    "makstid": "2020-07-12T09:20:32.262525",
    "system_read_count": 0,
    "@event_name": "vedtaksperiode_endret",
    "@id": "9154ce4d-cb8a-4dc4-96e1-379c91f76d02",
    "@opprettet": "2020-06-12T09:20:56.552561",
    "@forårsaket_av": {
        "event_name": "ny_søknad",
        "id": "75be4efa-fa13-44a9-afc2-6583dd87d626",
        "opprettet": "2020-06-12T09:20:31.985479"
    },
    "aktørId": "42",
    "fødselsnummer": "$FØDSELSNUMMER"
}
"""


private data class IdSett(
    val sykmeldingDokumentId: UUID = UUID.randomUUID(),
    val søknadDokumentId: UUID = UUID.randomUUID(),
    val inntektsmeldingDokumentId: UUID = UUID.randomUUID(),
    val nySøknadHendelseId: UUID = UUID.randomUUID(),
    val sendtSøknadHendelseId: UUID = UUID.randomUUID(),
    val inntektsmeldingHendelseId: UUID = UUID.randomUUID(),
    val vedtaksperiodeId: UUID = UUID.randomUUID(),
    val utbetalingId: UUID = UUID.randomUUID()
)
}

