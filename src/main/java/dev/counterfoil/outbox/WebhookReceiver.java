package dev.counterfoil.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.counterfoil.config.CounterfoilProperties;
import dev.counterfoil.config.TxRunner;
import dev.counterfoil.ledger.LedgerException;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Receiver side of webhooks. Verifies the signature and timestamp, then applies the event's effect and records
 * its id in {@code inbound_events} in ONE transaction. The primary key on (source, event_id) makes "apply once"
 * a database fact: a duplicate delivery inserts nothing and applies nothing. If applying fails, the id row rolls
 * back with it, so the sender's retry is processed normally (I7).
 */
@Component
public class WebhookReceiver {
    public enum Result { APPLIED, DUPLICATE }

    public static class InvalidSignatureException extends LedgerException {
        public InvalidSignatureException() {
            super("INVALID_SIGNATURE", 401, "webhook signature missing, wrong, or timestamp outside the tolerance window");
        }
    }

    private final JdbcClient jdbc;
    private final TxRunner tx;
    private final CounterfoilProperties props;
    private final ObjectMapper mapper;

    public WebhookReceiver(JdbcClient jdbc, TxRunner tx, CounterfoilProperties props, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
        this.mapper = mapper;
    }

    public Result receive(String source, String secret, String signatureHeader, String body, Consumer<JsonNode> effect) {
        if (!WebhookSigner.verify(secret, signatureHeader, body, Instant.now().getEpochSecond(), props.webhook().toleranceSeconds())) {
            throw new InvalidSignatureException();
        }
        JsonNode json;
        try {
            json = mapper.readTree(body);
        } catch (Exception e) {
            throw new dev.counterfoil.ledger.InvalidEntryException("webhook body is not JSON");
        }
        String eventId = json.path("id").asText("");
        if (eventId.isBlank()) {
            throw new dev.counterfoil.ledger.InvalidEntryException("webhook has no event id");
        }
        return tx.run(() -> {
            boolean first = jdbc.sql("INSERT INTO inbound_events (source, event_id) VALUES (:s, :e) ON CONFLICT DO NOTHING")
                    .param("s", source).param("e", eventId).update() == 1;
            if (!first) {
                return Result.DUPLICATE;
            }
            effect.accept(json);
            return Result.APPLIED;
        });
    }
}
