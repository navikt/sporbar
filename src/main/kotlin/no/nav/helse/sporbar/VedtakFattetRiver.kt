package no.nav.helse.sporbar

import com.fasterxml.jackson.databind.JsonNode
import no.nav.helse.rapids_rivers.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.UUID

private val log: Logger = LoggerFactory.getLogger("sporbar")

internal class VedtakFattetRiver(
    rapidsConnection: RapidsConnection,
    private val vedtakFattetMediator: VedtakFattetMediator
) : River.PacketListener {

    init {
        River(rapidsConnection).apply {
            validate {
                it.requireValue("@event_name", "vedtak_fattet")
                it.requireKey(
                    "aktørId",
                    "fødselsnummer",
                    "@id",
                    "vedtaksperiodeId",
                    "organisasjonsnummer",
                    "hendelser",
                    "sykepengegrunnlag",
                    "inntekt"
                )
                it.require("fom", JsonNode::asLocalDate)
                it.require("tom", JsonNode::asLocalDate)
                it.require("skjæringstidspunkt", JsonNode::asLocalDate)
                it.require("@opprettet", JsonNode::asLocalDateTime)
                it.interestedIn("utbetalingId") { id -> UUID.fromString(id.asText()) }

            }
        }.register(this)
    }

    override fun onPacket(packet: JsonMessage, context: MessageContext) {
        val fødselsnummer = packet["fødselsnummer"].asText()
        val aktørId = packet["aktørId"].asText()
        val organisasjonsnummer = packet["organisasjonsnummer"].asText()
        val fom = packet["fom"].asLocalDate()
        val tom = packet["tom"].asLocalDate()
        val skjæringstidspunkt = packet["skjæringstidspunkt"].asLocalDate()
        val hendelseIder = packet["hendelser"].map { UUID.fromString(it.asText()) }
        val inntekt = packet["inntekt"].asDouble()
        val sykepengegrunnlag = packet["sykepengegrunnlag"].asDouble()

        val utbetalingId = packet["utbetalingId"].takeUnless(JsonNode::isMissingOrNull)?.let {
            UUID.fromString(it.asText())}

        vedtakFattetMediator.vedtakFattet(VedtakFattet(
            fødselsnummer = fødselsnummer,
            aktørId = aktørId,
            organisasjonsnummer = organisasjonsnummer,
            fom = fom,
            tom = tom,
            skjæringstidspunkt = skjæringstidspunkt,
            hendelseIder = hendelseIder,
            inntekt = inntekt,
            sykepengegrunnlag = sykepengegrunnlag,
            utbetalingId = utbetalingId
        ))
        log.info("Behandler vedtakFattet: ${packet["@id"].asText()}")
    }
}

internal data class VedtakFattet(
    val fødselsnummer: String,
    val aktørId: String,
    val organisasjonsnummer: String,
    val fom: LocalDate,
    val tom: LocalDate,
    val skjæringstidspunkt: LocalDate,
    val hendelseIder: List<UUID>,
    val inntekt: Double,
    val sykepengegrunnlag: Double,
    val utbetalingId: UUID?
)
