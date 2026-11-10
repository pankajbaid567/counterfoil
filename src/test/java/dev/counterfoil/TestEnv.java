package dev.counterfoil;

import dev.counterfoil.admin.InvariantChecker;
import dev.counterfoil.config.CounterfoilProperties;
import dev.counterfoil.config.TxRunner;
import dev.counterfoil.outbox.OutboxService;
import dev.counterfoil.outbox.WebhookDispatcher;
import dev.counterfoil.outbox.WebhookReceiver;
import dev.counterfoil.outbox.WebhookSender;
import dev.counterfoil.payments.SettlementService;
import dev.counterfoil.payments.PaymentInvariants;
import dev.counterfoil.payments.PaymentService;
import dev.counterfoil.payments.PspSimulator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import dev.counterfoil.idempotency.IdempotencyService;
import dev.counterfoil.ledger.LedgerService;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Wires the real services over a fresh database, without booting Spring. */
public final class TestEnv {
    public final DataSource ds;
    public final JdbcClient jdbc;
    public final TxRunner tx;
    public final LedgerService ledger;
    public final InvariantChecker invariants;
    public final IdempotencyService idempotency;
    public final CounterfoilProperties props = CounterfoilProperties.defaults();
    public final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
    public final PspSimulator psp;
    public final OutboxService outbox;
    public final PaymentService payments;
    public final dev.counterfoil.payments.ReconciliationService reconciliation;
    public final WebhookReceiver receiver;
    public final SettlementService settlement;
    public volatile WebhookSender sender = (url, headers, body) -> 200;
    public final WebhookDispatcher dispatcher;

    public TestEnv(String name, int pool) {
        this.ds = TestPostgres.newDataSource(name, pool);
        this.jdbc = JdbcClient.create(ds);
        this.tx = new TxRunner(new DataSourceTransactionManager(ds));
        this.ledger = new LedgerService(jdbc, tx);
        this.invariants = new InvariantChecker(jdbc);
        this.idempotency = new IdempotencyService(jdbc, tx);
        this.psp = new PspSimulator(jdbc, props);
        this.outbox = new OutboxService(jdbc, mapper);
        this.payments = new PaymentService(jdbc, tx, ledger, idempotency, psp, outbox, props, mapper);
        this.reconciliation = new dev.counterfoil.payments.ReconciliationService(jdbc, tx);
        this.receiver = new WebhookReceiver(jdbc, tx, props, mapper);
        this.settlement = new SettlementService(ledger, receiver, idempotency, props);
        this.dispatcher = new WebhookDispatcher(jdbc, tx, fastBackoffProps(props), (url, h, b) -> sender.post(url, h, b));
        new PaymentInvariants(invariants);
        payments.ensureSystemAccounts();
    }

    static CounterfoilProperties fastBackoffProps(CounterfoilProperties p) {
        var w = p.webhook();
        return new CounterfoilProperties(p.apiKey(), new CounterfoilProperties.Webhook(w.secret(), "http://receiver.test/hook", true, 8, w.toleranceSeconds(), 1, 5), p.psp(), p.recovery(), p.fees());
    }

    public static boolean longRun() {
        return "1".equals(System.getenv("COUNTERFOIL_LONG"));
    }

    public void assertInvariants() {
        var report = invariants.run();
        if (!report.ok()) {
            throw new AssertionError("invariants violated: " + report.checks().stream().filter(c -> c.violations() > 0).toList());
        }
    }
}
