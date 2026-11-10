# Results

Everything here was measured on 2026-11-10 on one laptop (Apple M3, 8 cores, 8 GB, macOS, JDK 25 running a Java 21 target, PostgreSQL 16 via Homebrew). The laptop was busy with a browser and Spotlight during every run. Client, app and database share the same CPUs. None of this is a production claim.

## Tests
75 tests, 0 failures (`./mvnw test`). They run against a real PostgreSQL, never an in-memory substitute.

| Test | What it shows |
|---|---|
| `LedgerDifferentialTest` | 100,000 random operations (post, reverse, bad input) against a plain in-memory reference ledger: same outcome and same balances every time (`COUNTERFOIL_LONG=1`; the default is 3,000). |
| `LedgerConcurrencyTest` | 64 threads for 60 s: 668,275 postings, 1,310 refused for insufficient funds, **0 deadlock retries**, every invariant intact. In-process, no HTTP. |
| `PaymentServiceTest` (21) | PSP timeout before and after applying, crash while applying the outcome, 8 threads racing one capture, 8 threads racing one idempotency key, concurrent refunds, outbox failure rolling the whole create back. |
| `WebhookTest` | 60 events through a sender that fails 30% and duplicates 20%: all delivered, each applied exactly once; crash after send; DEAD after the attempt limit; parallel dispatchers never overlap. |
| `ReconciliationTest`, `ApiTest`, `IdempotencyTest`, `InvariantCheckerTest`, schema tests | See `docs/testing.md`. |

## Crash campaign: 1,000 runs, 0 violations
Command: `java tools/crashtest/CrashTest.java --workers 5 --cycles 200 --clients 6 --seed 20261110`. Raw per-run rows: `docs/crash-campaign-1000.csv`.

- A **run** is one SIGKILL of the real application JVM at a random moment under load, followed by a restart and verification. 898 were kills during normal operation (0.3 to 2.5 s of load before the kill), 102 were kills during startup or migration.
- Workload: transfers, and payments through authorize, capture, void and refund with injected PSP faults (5% decline, 8% timeout before applying, 8% timeout after applying), 6 client threads per worker, with webhooks delivered to a real HTTP receiver that verifies signatures.
- Clients keep their in-flight request and retry it with the same Idempotency-Key after each restart.
- After each restart, before any retry, the harness checked that nothing a client had been told succeeded was missing (4,670,417 acknowledged facts checked in total), that no row existed that no client asked for, that payment statuses only moved forward, and that the server's own invariants and the trial balance held.
- At the end it checked exactly-once effects (transfers, payments and refunds completed equals rows present), no payment operation left pending, all outbox events delivered and seen by the receiver, none dead.
- Totals: 41,785 tasks completed, **0 violations**, 0 bad webhook signatures, 92,792 webhook events received, of which 29 were delivered more than once (the "sent but not yet recorded" window, absorbed by receiver de-duplication).
- Wall time was 5,724 s. The verification step was slower than it needed to be (one `psql` call per refund); it is now one query, but this campaign ran the old version.

What this does **not** show: PostgreSQL itself was never killed (its crash safety is PostgreSQL's), the disk was never failed, and 1,000 runs bound a failure rate rather than prove there is none.

## Mutation check: 7 of 7 caught
`python3 scripts/mutation_check.py`. Each mutant is a deliberate bug applied to a scratch copy; every patch asserts that its target text exists.

| # | Injected bug | Caught by |
|---|---|---|
| 1 | Lock order depends on transfer direction | `LedgerConcurrencyTest` (deadlocks) |
| 2 | Overdraft check removed | schema, ledger and payment tests |
| 3 | Idempotency claim never inserted | `IdempotencyTest` |
| 4 | A payment can be captured twice | `PaymentServiceTest` |
| 5 | Outbox event written after the commit instead of inside it | `PaymentServiceTest` (outbox write fails once) |
| 6 | Receiver de-duplication removed | `WebhookTest` |
| 7 | Idempotency key claimed in a separate transaction | the crash harness only: a request stuck in progress forever |

Honest notes on how this table was reached:
- The first run caught 5 of 7. **Mutant 1 survived because it was equivalent, not because the tests were weak**: it only deleted a sort, but lines are already merged in a `TreeMap` by account code, which is itself a global lock order. It was rewritten as a real bug (debits first) and is now caught.
- **Mutant 5 survived the crash harness**, in the first run (3 workers x 40 kills) and again in a dedicated run of 3 workers x 150 cycles (about 450 kills). Its window is roughly a millisecond, and at this workload the chance that a kill lands inside it is low. A deterministic fault-injection unit test now catches it. The takeaway is the division of labour: the crash harness finds long windows (mutant 7 was found within 120 kills), fault-injection tests cover millisecond ones.
- Mutant 7 is the case that justifies the harness: every unit test passes with it.

## Throughput and latency
`java tools/bench/Bench.java --seconds 20 --clients 16`, real jar over HTTP, PostgreSQL with **fsync on** (each commit waits for the WAL flush), 200 accounts, pool of 20 connections. Closed loop: each client sends its next request after the previous reply, so latency is service time at that concurrency, not open-loop latency. Three runs; raw CSVs in `tools/bench/`.

| Workload | Requests/s (3 runs) | p50 | p95 | p99 |
|---|---|---|---|---|
| Journal entry (2 lines, random account pair) | 6,437 / 6,328 / 6,373 | about 2.0 ms | about 4.6 ms | 8.9 to 11.3 ms |
| Payment flow: create, authorize, capture (each request counted) | 2,178 / 2,256 / 2,147 | about 3.8 ms | 19.7 to 22.3 ms | 56 to 65 ms |

The payment flow is about 725 complete payments per second (3 requests each). Zero request errors in all runs. The invariant checker was not run on the benchmark database. The PSP is a simulator that answers instantly, so real PSP latency would dominate in practice.

## Not measured
Anything on the hosted free tier, multi-node behaviour, PostgreSQL failover, load beyond one machine, the Docker image (no Docker daemon was available), and the CI workflow (not yet pushed).
