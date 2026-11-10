package dev.counterfoil.config;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a unit of work in one READ COMMITTED transaction and retries it when PostgreSQL aborts it with a
 * deadlock (40P01) or serialization failure (40001). The retry boundary is the transaction, so a retried
 * attempt starts from a clean slate. Retries are counted so tests can assert on them (ADR-005, ADR-011).
 */
@Component
public class TxRunner {
    private static final int MAX_ATTEMPTS = 6;
    private final TransactionTemplate template;
    private final AtomicLong retries = new AtomicLong();

    public TxRunner(PlatformTransactionManager tm) {
        this.template = new TransactionTemplate(tm);
    }

    public <T> T run(Supplier<T> work) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return template.execute(status -> work.get());
            } catch (RuntimeException e) {
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                last = e;
                retries.incrementAndGet();
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(2, 8L * attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    public void runVoid(Runnable work) {
        run(() -> {
            work.run();
            return null;
        });
    }

    /** How many transactions were retried after a deadlock or serialization failure. */
    public long retries() {
        return retries.get();
    }

    public static boolean isRetryable(Throwable t) {
        return SqlErrors.sqlState(t).map(s -> s.equals("40P01") || s.equals("40001")).orElse(false);
    }
}
