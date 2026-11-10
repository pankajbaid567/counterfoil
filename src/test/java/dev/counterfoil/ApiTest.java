package dev.counterfoil;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.counterfoil.outbox.WebhookSigner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiTest {
    static final String KEY = "test-api-key-0123456789";
    static final ObjectMapper M = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestPostgres.ensureStarted();
        r.add("spring.datasource.url", () -> TestPostgres.newDatabaseUrl("api"));
        r.add("spring.datasource.username", TestPostgres::user);
        r.add("spring.datasource.password", TestPostgres::password);
        r.add("counterfoil.api-key", () -> KEY);
        r.add("counterfoil.webhook.dispatcher-enabled", () -> "false");
    }

    @LocalServerPort int port;

    record Res(int status, String body, HttpResponse<String> raw) {
        JsonNode json() throws Exception { return M.readTree(body); }
    }

    Res call(String method, String path, String key, String idem, String body, String... extra) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        if (key != null) b.header("Authorization", "Bearer " + key);
        if (idem != null) b.header("Idempotency-Key", idem);
        for (int i = 0; i < extra.length; i += 2) b.header(extra[i], extra[i + 1]);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r);
    }

    Res post(String path, String idem, String body) throws Exception { return call("POST", path, KEY, idem, body); }
    Res get(String path) throws Exception { return call("GET", path, KEY, null, null); }
    static String k() { return "k-" + UUID.randomUUID(); }

    @Test
    void authIsRequiredOnEverythingExceptTheSignedWebhook() throws Exception {
        assertThat(call("GET", "/v1/trial-balance", null, null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/v1/trial-balance", "wrong-key-wrong-key-1", null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/v1/trial-balance", KEY, null, null).status()).isEqualTo(200);
        var wh = call("POST", "/v1/webhooks/psp", null, null, "{}");
        assertThat(wh.status()).as("reaches the signature check, not the key check").isEqualTo(401);
        assertThat(wh.json().at("/error/code").asText()).isEqualTo("INVALID_SIGNATURE");
    }

    @Test
    void journalEntryLifecycleWithIdempotencyAndErrorModel() throws Exception {
        assertThat(post("/v1/accounts", null, "{\"code\":\"cash-a\",\"name\":\"Cash\",\"type\":\"ASSET\",\"currency\":\"INR\",\"allow_negative\":true}").status()).isEqualTo(201);
        assertThat(post("/v1/accounts", null, "{\"code\":\"equity-a\",\"name\":\"Equity\",\"type\":\"EQUITY\",\"currency\":\"INR\"}").status()).isEqualTo(201);
        assertThat(post("/v1/accounts", null, "{\"code\":\"equity-a\",\"name\":\"Equity\",\"type\":\"EQUITY\",\"currency\":\"INR\"}").status()).isEqualTo(409);
        assertThat(post("/v1/accounts", null, "{\"code\":\"merchant:x:payable\",\"name\":\"n\",\"type\":\"LIABILITY\",\"currency\":\"INR\"}").status()).isEqualTo(400);

        String body = "{\"reference\":\"inv-1\",\"lines\":[{\"account\":\"cash-a\",\"side\":\"D\",\"amount\":500},{\"account\":\"equity-a\",\"side\":\"C\",\"amount\":500}]}";
        String key = k();
        Res first = post("/v1/journal-entries", key, body);
        assertThat(first.status()).isEqualTo(201);
        Res again = post("/v1/journal-entries", key, body);
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.raw().headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(post("/v1/journal-entries", key, body.replace("500", "600")).status()).isEqualTo(422);
        assertThat(get("/v1/accounts/cash-a/balance").json().path("natural").asLong()).isEqualTo(500);

        String id = first.json().path("public_id").asText();
        assertThat(get("/v1/journal-entries/" + id).status()).isEqualTo(200);
        assertThat(post("/v1/journal-entries/" + id + "/reverse", k(), null).status()).isEqualTo(201);
        assertThat(post("/v1/journal-entries/" + id + "/reverse", k(), null).status()).as("already reversed").isEqualTo(409);
        assertThat(get("/v1/accounts/cash-a/balance").json().path("natural").asLong()).isZero();

        Res unbalanced = post("/v1/journal-entries", k(), "{\"lines\":[{\"account\":\"cash-a\",\"side\":\"D\",\"amount\":5},{\"account\":\"equity-a\",\"side\":\"C\",\"amount\":4}]}");
        assertThat(unbalanced.status()).isEqualTo(422);
        assertThat(unbalanced.json().at("/error/code").asText()).isNotBlank();
    }

    @Test
    void malformedRequestsGetCleanFourHundreds() throws Exception {
        assertThat(post("/v1/journal-entries", null, "{}").status()).as("no Idempotency-Key").isEqualTo(400);
        assertThat(post("/v1/journal-entries", "bad key!", "{}").status()).isEqualTo(400);
        assertThat(post("/v1/journal-entries", k(), "not json").status()).isEqualTo(400);
        assertThat(post("/v1/journal-entries", k(), "{\"lines\":[],\"surprise\":1}").status()).as("unknown field").isEqualTo(400);
        assertThat(post("/v1/journal-entries", k(), null).status()).isEqualTo(400);
        assertThat(get("/v1/journal-entries/not-a-uuid").status()).isEqualTo(400);
        assertThat(get("/v1/journal-entries/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(get("/v1/nope").status()).isEqualTo(404);
        assertThat(call("GET", "/v1/accounts/cash-a/statement?limit=0", KEY, null, null).status()).isEqualTo(400);
        assertThat(get("/v1/accounts/ghost/balance").status()).isEqualTo(404);
    }

    @Test
    void fullPaymentFlowOverHttpThenPayoutWebhookAndReconciliation() throws Exception {
        Res created = post("/v1/payments", k(), "{\"merchant_id\":\"shop1\",\"amount\":10000,\"currency\":\"INR\"}");
        assertThat(created.status()).isEqualTo(201);
        String id = created.json().path("id").asText();
        assertThat(post("/v1/payments/" + id + "/capture", k(), null).status()).as("not authorized yet").isEqualTo(409);
        assertThat(post("/v1/payments/" + id + "/authorize", k(), null).json().path("status").asText()).isEqualTo("AUTHORIZED");
        String capKey = k();
        Res cap = post("/v1/payments/" + id + "/capture", capKey, null);
        assertThat(cap.json().path("status").asText()).isEqualTo("CAPTURED");
        assertThat(post("/v1/payments/" + id + "/capture", capKey, null).raw().headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(post("/v1/payments/" + id + "/refunds", k(), "{\"amount\":3000}").json().path("status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(post("/v1/payments/" + id + "/refunds", k(), "{\"amount\":99999}").status()).isEqualTo(409);
        assertThat(get("/v1/payments/" + id).json().path("refunded_amount").asLong()).isEqualTo(3000);

        // the PSP settles 7,000 into our bank; delivered twice
        long now = Instant.now().getEpochSecond();
        String evt = "{\"id\":\"evt-" + UUID.randomUUID() + "\",\"type\":\"psp.settlement.paid\",\"data\":{\"amount\":7000,\"reference\":\"b-" + UUID.randomUUID() + "\"}}";
        String sig = WebhookSigner.sign("dev-only-webhook-secret", now, evt);
        Res w1 = call("POST", "/v1/webhooks/psp", null, null, evt, "Counterfoil-Signature", sig);
        Res w2 = call("POST", "/v1/webhooks/psp", null, null, evt, "Counterfoil-Signature", sig);
        assertThat(w1.json().path("result").asText()).isEqualTo("APPLIED");
        assertThat(w2.json().path("result").asText()).isEqualTo("DUPLICATE");
        assertThat(call("POST", "/v1/webhooks/psp", null, null, evt + " ", "Counterfoil-Signature", sig).status()).as("tampered body").isEqualTo(401);

        assertThat(post("/v1/merchants/shop1/payouts", k(), "{\"amount\":999999}").status()).as("more than owed").isEqualTo(409);
        assertThat(post("/v1/merchants/shop1/payouts", k(), "{\"amount\":6860}").status()).isEqualTo(201);
        assertThat(get("/v1/accounts/merchant:shop1:payable/balance").json().path("natural").asLong()).isZero();

        String stmt = "{\"period_from\":\"" + Instant.now().minusSeconds(600) + "\",\"period_to\":\"" + Instant.now().plusSeconds(600) + "\",\"lines\":[{\"reference\":\"ghost\",\"amount\":5}]}";
        Res rec = post("/v1/reconciliations", null, stmt);
        assertThat(rec.status()).isEqualTo(201);
        assertThat(rec.json().path("items").size()).isGreaterThanOrEqualTo(2);
        assertThat(post("/v1/reconciliations", null, stmt).status()).isEqualTo(200);
        assertThat(get("/v1/reconciliations/" + rec.json().path("id").asText()).status()).isEqualTo(200);

        Res inv = get("/v1/admin/invariants");
        assertThat(inv.json().path("ok").asBoolean()).as(inv.body()).isTrue();
        assertThat(get("/v1/trial-balance").json().path("balanced").asBoolean()).isTrue();
    }

    @Test
    void internalErrorsDoNotLeakDetails() throws Exception {
        Res r = post("/v1/payments", k(), "{\"merchant_id\":\"BAD ID\",\"amount\":1,\"currency\":\"INR\"}");
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body()).doesNotContain("Exception").doesNotContain("org.springframework").doesNotContain("SQL");
    }
}
