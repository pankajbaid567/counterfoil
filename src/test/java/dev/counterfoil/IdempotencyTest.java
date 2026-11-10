package dev.counterfoil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.counterfoil.idempotency.IdempotencyException;
import dev.counterfoil.idempotency.IdempotencyService;
import dev.counterfoil.idempotency.IdempotencyService.Response;
import dev.counterfoil.idempotency.RequestHasher;
import dev.counterfoil.ledger.*;
import dev.counterfoil.ledger.Records.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IdempotencyTest {
    static TestEnv env;

    @BeforeAll
    static void setUp() {
        env = new TestEnv("idem", 32);
        env.ledger.createAccount("a", null, AccountType.ASSET, "INR", true);
        env.ledger.createAccount("b", null, AccountType.EQUITY, "INR", true);
    }

    Response post(long amount) {
        EntryView e = env.ledger.postInTx(new PostRequest("idem", null, List.of(new LineRequest("a", Side.D, amount), new LineRequest("b", Side.C, amount))));
        return new Response(201, "{\"entry\":\"" + e.publicId() + "\"}", "entry", e.publicId().toString());
    }

    long entries() {
        return env.jdbc.sql("SELECT count(*) FROM journal_entries WHERE reference = 'idem'").query(Long.class).single();
    }

    @Test
    void sameKeySameBodyReplaysTheStoredResponseWithOneEffect() {
        long before = entries();
        String h = RequestHasher.hash("POST", "/v1/journal-entries", "{\"amount\":5}");
        var first = env.idempotency.runAtomic("t1", "key-1", h, () -> env.tx.run(() -> post(5)));
        var second = env.idempotency.runAtomic("t1", "key-1", h, () -> { throw new AssertionError("must not run again"); });
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.response()).isEqualTo(first.response());
        assertThat(entries() - before).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyIsRejectedAndNothingRuns() {
        long before = entries();
        env.idempotency.runAtomic("t2", "key-2", RequestHasher.hash("POST", "/x", "{\"a\":1}"), () -> post(1));
        assertThatThrownBy(() -> env.idempotency.runAtomic("t2", "key-2", RequestHasher.hash("POST", "/x", "{\"a\":2}"), () -> post(2)))
                .isInstanceOf(IdempotencyException.class).extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(entries() - before).isEqualTo(1);
    }

    @Test
    void keysAreScoped() {
        long before = entries();
        String h = RequestHasher.hash("POST", "/x", "{}");
        env.idempotency.runAtomic("tenant-a", "same", h, () -> post(1));
        env.idempotency.runAtomic("tenant-b", "same", h, () -> post(1));
        assertThat(entries() - before).isEqualTo(2);
    }

    @Test
    void fieldOrderDoesNotChangeTheHash() {
        assertThat(RequestHasher.hash("POST", "/x", "{\"a\":1,\"b\":{\"y\":2,\"x\":[3,{\"q\":1,\"p\":2}]}}"))
                .isEqualTo(RequestHasher.hash("POST", "/x", "{ \"b\" : {\"x\":[3,{\"p\":2,\"q\":1}],\"y\":2}, \"a\":1 }"));
        assertThat(RequestHasher.hash("POST", "/x", "{\"a\":1}")).isNotEqualTo(RequestHasher.hash("PUT", "/x", "{\"a\":1}"));
        assertThat(RequestHasher.hash("POST", "/x", "{\"a\":1}")).isNotEqualTo(RequestHasher.hash("POST", "/y", "{\"a\":1}"));
    }

    @Test
    void aFailedRequestStoresNothingAndCanBeRetried() {
        long before = entries();
        String h = RequestHasher.hash("POST", "/x", "{\"fail\":true}");
        assertThatThrownBy(() -> env.idempotency.runAtomic("t3", "key-3", h, () -> {
            post(7);
            throw new InvalidEntryException("boom after writing");       // rolls the whole transaction back
        })).isInstanceOf(InvalidEntryException.class);
        assertThat(entries() - before).isZero();
        assertThat(env.idempotency.find("t3", "key-3")).isEmpty();
        var ok = env.idempotency.runAtomic("t3", "key-3", h, () -> post(7));
        assertThat(ok.replayed()).isFalse();
        assertThat(entries() - before).isEqualTo(1);
    }

    @Test
    void sixteenConcurrentIdenticalRequestsProduceExactlyOneEffect() throws Exception {
        long before = entries();
        String h = RequestHasher.hash("POST", "/x", "{\"c\":1}");
        AtomicInteger executed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<IdempotencyService.Outcome>> fs = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return env.idempotency.runAtomic("t4", "key-4", h, () -> { executed.incrementAndGet(); return post(9); });
            }));
        }
        go.countDown();
        Set<Response> distinct = new HashSet<>();
        int replays = 0;
        for (var f : fs) {
            var o = f.get();
            distinct.add(o.response());
            replays += o.replayed() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(executed.get()).isEqualTo(1);
        assertThat(distinct).as("every caller sees the same response").hasSize(1);
        assertThat(replays).isEqualTo(15);
        assertThat(entries() - before).isEqualTo(1);
        env.assertInvariants();
    }

    @Test
    void aNewServiceInstanceStillReplays() {
        String h = RequestHasher.hash("POST", "/x", "{\"r\":1}");
        var first = env.idempotency.runAtomic("t5", "key-5", h, () -> post(3));
        var restarted = new IdempotencyService(env.jdbc, env.tx);          // as after a process restart: state is only in the database
        assertThat(restarted.runAtomic("t5", "key-5", h, () -> { throw new AssertionError(); }).response()).isEqualTo(first.response());
    }

    @Test
    void twoPhaseKeyReportsInProgressUntilCompleted() {
        String h = RequestHasher.hash("POST", "/x", "{\"p\":1}");
        assertThat(env.idempotency.begin("t6", "key-6", h)).isInstanceOf(IdempotencyService.Begin.Fresh.class);
        assertThatThrownBy(() -> env.idempotency.begin("t6", "key-6", h)).isInstanceOf(IdempotencyException.class).extracting("code").isEqualTo("REQUEST_IN_PROGRESS");
        env.tx.runVoid(() -> env.idempotency.completeInTx("t6", "key-6", new Response(200, "{}", "x", "1")));
        assertThat(env.idempotency.begin("t6", "key-6", h)).isInstanceOfSatisfying(IdempotencyService.Begin.Replay.class,
                r -> assertThat(r.response().status()).isEqualTo(200));
    }
}
