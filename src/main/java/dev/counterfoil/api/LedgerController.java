package dev.counterfoil.api;

import static dev.counterfoil.api.ApiSupport.required;

import dev.counterfoil.admin.InvariantChecker;
import dev.counterfoil.api.Dtos.*;
import dev.counterfoil.idempotency.IdempotencyService;
import dev.counterfoil.idempotency.IdempotencyService.Response;
import dev.counterfoil.idempotency.RequestHasher;
import dev.counterfoil.ledger.*;
import dev.counterfoil.ledger.Records.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
public class LedgerController {
    private final LedgerService ledger;
    private final IdempotencyService idem;
    private final ApiSupport api;
    private final InvariantChecker invariants;

    public LedgerController(LedgerService ledger, IdempotencyService idem, ApiSupport api, InvariantChecker invariants) {
        this.ledger = ledger;
        this.idem = idem;
        this.api = api;
        this.invariants = invariants;
    }

    @PostMapping("/accounts")
    public ResponseEntity<String> createAccount(@RequestBody(required = false) String body) {
        AccountBody a = api.parse(body, AccountBody.class);
        String code = required(a.code(), "code");
        if (LedgerService.isReserved(code)) {
            throw ApiException.bad("account '" + code + "' is reserved for payment flows");
        }
        Account created = ledger.createAccount(code, required(a.name(), "name"), required(a.type(), "type"), required(a.currency(), "currency"),
                a.allowNegative() != null && a.allowNegative());
        return api.json(201, created);
    }

    @GetMapping("/accounts/{code}/balance")
    public ResponseEntity<String> balance(@PathVariable String code) {
        return api.json(200, ledger.balance(code));
    }

    @GetMapping("/accounts/{code}/statement")
    public ResponseEntity<String> statement(@PathVariable String code, @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "100") int limit) {
        if (limit < 1 || limit > 500 || after < 0) {
            throw ApiException.bad("limit must be 1-500 and after must be >= 0");
        }
        List<StatementLine> lines = ledger.statement(code, after, limit);
        Long next = lines.size() == limit ? lines.get(lines.size() - 1).lineId() : null;
        return api.json(200, new StatementPage(lines, next));
    }

    public record StatementPage(List<StatementLine> lines, Long nextAfter) {}

    @PostMapping("/journal-entries")
    public ResponseEntity<String> post(@RequestHeader(value = "Idempotency-Key", required = false) String keyHeader, @RequestBody(required = false) String body) {
        String key = api.key(keyHeader);
        EntryBody e = api.parse(body, EntryBody.class);
        List<LineRequest> lines = required(e.lines(), "lines").stream()
                .map(l -> new LineRequest(required(l, "lines[]").account(), required(l.side(), "side"), required(l.amount(), "amount"))).toList();
        var outcome = idem.runAtomic(ApiSupport.SCOPE, key, RequestHasher.hash("POST", "/v1/journal-entries", body), () -> {
            EntryView v = ledger.postInTx(new PostRequest(e.reference(), e.description(), lines));
            return new Response(201, toJson(v), "journal_entry", v.publicId().toString());
        });
        return api.render(outcome);
    }

    @GetMapping("/journal-entries/{id}")
    public ResponseEntity<String> entry(@PathVariable UUID id) {
        return api.json(200, ledger.findEntry(id).orElseThrow(() -> new NotFoundException("no entry " + id)));
    }

    @PostMapping("/journal-entries/{id}/reverse")
    public ResponseEntity<String> reverse(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String keyHeader,
                                          @RequestBody(required = false) String body) {
        String key = api.key(keyHeader);
        ReverseBody r = body == null || body.isBlank() ? new ReverseBody(null) : api.parse(body, ReverseBody.class);
        String ref = r.reference() != null ? r.reference() : "reversal:" + id;
        var outcome = idem.runAtomic(ApiSupport.SCOPE, key, RequestHasher.hash("POST", "/v1/journal-entries/" + id + "/reverse", body), () -> {
            EntryView v = ledger.reverseInTx(id, ref);
            return new Response(201, toJson(v), "journal_entry", v.publicId().toString());
        });
        return api.render(outcome);
    }

    @GetMapping("/trial-balance")
    public ResponseEntity<String> trialBalance() {
        return api.json(200, ledger.trialBalance());
    }

    @GetMapping("/admin/invariants")
    public ResponseEntity<String> invariants() {
        return api.json(200, invariants.run());
    }

    private String toJson(Object o) {
        return api.json(200, o).getBody();
    }
}
