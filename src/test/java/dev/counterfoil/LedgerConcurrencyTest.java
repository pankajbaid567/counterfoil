package dev.counterfoil;

import static org.assertj.core.api.Assertions.assertThat;

import dev.counterfoil.ledger.*;
import dev.counterfoil.ledger.Records.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Many threads, hot accounts, opposite-order transfers: the invariants must hold and no deadlock may occur. */
class LedgerConcurrencyTest {

    @Test
    void randomTransfersOnHotAccountsKeepEveryInvariantAndNeverDeadlock() throws Exception {
        TestEnv env = new TestEnv("concurrency", 64);
        int accounts = 20, threads = 64;
        long seconds = TestEnv.longRun() ? 60 : 6;
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < accounts; i++) {
            boolean guarded = i < 6;                         // six accounts forbid a negative balance
            String code = "acct-" + i;
            env.ledger.createAccount(code, null, guarded ? AccountType.LIABILITY : AccountType.ASSET, "INR", !guarded);
            codes.add(code);
        }
        env.ledger.createAccount("funding", null, AccountType.EQUITY, "INR", true);
        for (int i = 0; i < 6; i++) {                        // give the guarded accounts something to spend
            env.ledger.post(new PostRequest(null, "seed", List.of(new LineRequest("funding", Side.D, 1_000), new LineRequest(codes.get(i), Side.C, 1_000))));
        }
        AtomicLong ok = new AtomicLong(), insufficient = new AtomicLong(), unexpected = new AtomicLong();
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final long seed = t;
            fs.add(pool.submit(() -> {
                Random r = new Random(seed);
                while (System.nanoTime() < end) {
                    int a = r.nextInt(accounts), b = r.nextInt(accounts);
                    if (a == b) continue;
                    long amount = 1 + r.nextInt(50);
                    try {
                        env.ledger.post(new PostRequest(null, null, List.of(
                                new LineRequest(codes.get(a), Side.D, amount), new LineRequest(codes.get(b), Side.C, amount))));
                        ok.incrementAndGet();
                    } catch (InsufficientFundsException e) {
                        insufficient.incrementAndGet();
                    } catch (Throwable e) {
                        unexpected.incrementAndGet();
                        e.printStackTrace();
                    }
                }
            }));
        }
        for (var f : fs) f.get();
        pool.shutdown();

        assertThat(unexpected.get()).as("unexpected failures (deadlocks would show up here)").isZero();
        assertThat(env.tx.retries()).as("transactions retried after a deadlock: lines are inserted in account order, so none").isZero();
        env.assertInvariants();
        assertThat(env.ledger.trialBalance().balanced()).isTrue();
        long entries = env.jdbc.sql("SELECT count(*) FROM journal_entries").query(Long.class).single();
        assertThat(entries).isEqualTo(ok.get() + 6);          // every success is exactly one entry, every failure left nothing
        System.out.printf("concurrency: %d threads, %d s, %d posts ok, %d refused for insufficient funds, %d retries%n",
                threads, seconds, ok.get(), insufficient.get(), env.tx.retries());
        assertThat(ok.get()).isGreaterThan(100);
    }
}
