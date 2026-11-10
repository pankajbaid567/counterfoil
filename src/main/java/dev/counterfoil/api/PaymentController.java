package dev.counterfoil.api;

import dev.counterfoil.api.Dtos.*;
import dev.counterfoil.idempotency.IdempotencyService.Outcome;
import dev.counterfoil.idempotency.RequestHasher;
import dev.counterfoil.outbox.WebhookReceiver;
import dev.counterfoil.payments.*;
import dev.counterfoil.payments.Payments.*;
import dev.counterfoil.payments.ReconciliationService.BankLine;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class PaymentController {
    private final PaymentService payments;
    private final SettlementService settlement;
    private final ReconciliationService reconciliation;
    private final ApiSupport api;

    public PaymentController(PaymentService payments, SettlementService settlement, ReconciliationService reconciliation, ApiSupport api) {
        this.payments = payments;
        this.settlement = settlement;
        this.reconciliation = reconciliation;
        this.api = api;
    }

    private static final String KEY = "Idempotency-Key";

    @PostMapping("/payments")
    public ResponseEntity<String> create(@RequestHeader(value = KEY, required = false) String k, @RequestBody(required = false) String body) {
        String key = api.key(k);
        CreateRequest r = api.parse(body, CreateRequest.class);
        return api.render(payments.create(ApiSupport.SCOPE, key, RequestHasher.hash("POST", "/v1/payments", body), r));
    }

    @GetMapping("/payments/{id}")
    public ResponseEntity<String> get(@PathVariable UUID id) {
        return api.json(200, payments.get(id));
    }

    @PostMapping("/payments/{id}/authorize")
    public ResponseEntity<String> authorize(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String k) {
        return api.render(payments.authorize(ApiSupport.SCOPE, api.key(k), RequestHasher.hash("POST", "/v1/payments/" + id + "/authorize", null), id));
    }

    @PostMapping("/payments/{id}/capture")
    public ResponseEntity<String> capture(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String k) {
        return api.render(payments.capture(ApiSupport.SCOPE, api.key(k), RequestHasher.hash("POST", "/v1/payments/" + id + "/capture", null), id));
    }

    @PostMapping("/payments/{id}/void")
    public ResponseEntity<String> voidIt(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String k) {
        return api.render(payments.voidPayment(ApiSupport.SCOPE, api.key(k), RequestHasher.hash("POST", "/v1/payments/" + id + "/void", null), id));
    }

    @PostMapping("/payments/{id}/refunds")
    public ResponseEntity<String> refund(@PathVariable UUID id, @RequestHeader(value = KEY, required = false) String k, @RequestBody(required = false) String body) {
        String key = api.key(k);
        RefundRequest r = api.parse(body, RefundRequest.class);
        return api.render(payments.refund(ApiSupport.SCOPE, key, RequestHasher.hash("POST", "/v1/payments/" + id + "/refunds", body), id, r));
    }

    @PostMapping("/merchants/{merchantId}/payouts")
    public ResponseEntity<String> payout(@PathVariable String merchantId, @RequestHeader(value = KEY, required = false) String k,
                                         @RequestBody(required = false) String body) {
        String key = api.key(k);
        PayoutBody p = api.parse(body, PayoutBody.class);
        Outcome o = settlement.payout(ApiSupport.SCOPE, key, RequestHasher.hash("POST", "/v1/merchants/" + merchantId + "/payouts", body), merchantId,
                ApiSupport.required(p.amount(), "amount"));
        return api.render(o);
    }

    /** The PSP calls this. No API key: the HMAC signature over the raw body is the authentication. */
    @PostMapping("/webhooks/psp")
    public ResponseEntity<String> pspWebhook(@RequestHeader(value = "Counterfoil-Signature", required = false) String sig, @RequestBody(required = false) String body) {
        WebhookReceiver.Result r = settlement.onPspWebhook(sig, body == null ? "" : body);
        return api.json(200, Map.of("result", r.name()));
    }

    @PostMapping("/reconciliations")
    public ResponseEntity<String> reconcile(@RequestBody(required = false) String body) {
        ReconcileBody r = api.parse(body, ReconcileBody.class);
        var lines = ApiSupport.required(r.lines(), "lines").stream()
                .map(l -> new BankLine(ApiSupport.required(l, "lines[]").reference(), ApiSupport.required(l.amount(), "amount"))).toList();
        var res = reconciliation.reconcile(ApiSupport.required(r.periodFrom(), "period_from"), ApiSupport.required(r.periodTo(), "period_to"), lines);
        var response = api.json(res.replayed() ? 200 : 201, res);
        return res.replayed() ? ResponseEntity.status(200).headers(response.getHeaders()).header("Idempotent-Replayed", "true").body(response.getBody()) : response;
    }

    @GetMapping("/reconciliations/{id}")
    public ResponseEntity<String> reconciliation(@PathVariable UUID id) {
        return api.json(200, reconciliation.load(id, false));
    }
}
