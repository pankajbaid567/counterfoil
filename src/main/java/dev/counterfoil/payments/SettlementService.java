package dev.counterfoil.payments;

import com.fasterxml.jackson.databind.JsonNode;
import dev.counterfoil.config.CounterfoilProperties;
import dev.counterfoil.idempotency.IdempotencyService;
import dev.counterfoil.idempotency.IdempotencyService.Outcome;
import dev.counterfoil.idempotency.IdempotencyService.Response;
import dev.counterfoil.ledger.*;
import dev.counterfoil.ledger.Records.*;
import dev.counterfoil.outbox.WebhookReceiver;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Money movements that are not payments: PSP settlements into our bank (inbound webhook) and merchant payouts. */
@Service
public class SettlementService {
    private final LedgerService ledger;
    private final WebhookReceiver receiver;
    private final IdempotencyService idem;
    private final CounterfoilProperties props;

    public SettlementService(LedgerService ledger, WebhookReceiver receiver, IdempotencyService idem, CounterfoilProperties props) {
        this.ledger = ledger;
        this.receiver = receiver;
        this.idem = idem;
        this.props = props;
    }

    /**
     * Inbound {@code psp.settlement.paid}: the PSP paid us. Dr bank:settlement / Cr psp_clearing, applied exactly once
     * however many times the PSP delivers the event.
     */
    public WebhookReceiver.Result onPspWebhook(String signatureHeader, String body) {
        return receiver.receive("psp", props.webhook().secret(), signatureHeader, body, (JsonNode json) -> {
            String type = json.path("type").asText();
            if (!type.equals("psp.settlement.paid")) {
                throw new InvalidEntryException("unsupported PSP event type '" + type + "'");
            }
            long amount = json.path("data").path("amount").asLong(0);
            String reference = json.path("data").path("reference").asText(json.path("id").asText());
            if (amount <= 0) {
                throw new InvalidEntryException("settlement amount must be positive");
            }
            ledger.postInTx(new PostRequest(Kind.PSP_SETTLEMENT, "psp-settlement:" + reference, "PSP settlement",
                    List.of(new LineRequest(PaymentService.BANK, Side.D, amount), new LineRequest(PaymentService.CLEARING, Side.C, amount))));
        });
    }

    /** Pays out a merchant: Dr merchant payable / Cr bank:settlement. Idempotent; refused if the merchant is owed less. */
    public Outcome payout(String scope, String key, String requestHash, String merchantId, long amount) {
        if (amount <= 0) {
            throw new InvalidEntryException("payout amount must be positive");
        }
        return idem.runAtomic(scope, key, requestHash, () -> {
            EntryView e = ledger.postInTx(new PostRequest(Kind.PAYOUT, "payout:" + scope + ":" + key, "payout to " + merchantId,
                    List.of(new LineRequest(PaymentService.payable(merchantId), Side.D, amount), new LineRequest(PaymentService.BANK, Side.C, amount))));
            return new Response(201, "{\"entry_id\":\"" + e.publicId() + "\",\"merchant_id\":\"" + merchantId + "\",\"amount\":" + amount + "}", "payout", e.publicId().toString());
        });
    }
}
