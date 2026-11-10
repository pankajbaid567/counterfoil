# PRD: Counterfoil

The counterfoil is the stub you keep to prove a payment happened. This project is the backend that keeps that proof correct when requests are retried, webhooks arrive twice, two payments race for the same balance, and the process dies halfway through a payment.

## Problem
Payments interviews (Razorpay, Juspay, PhonePe, CRED, Paytm, fintech GCCs) probe the same few ideas: idempotency, consistency under concurrency, double-entry accounting, "what happens if it crashes between charging the card and recording it", and reconciliation. Most student projects show a CRUD "wallet" that gets all of these wrong. The gap analysis from Bodkin (`../Bodkin/docs/results.md`) put payments, transactions and Java at the top of the "no proven evidence" list.

## Product
A Java 21 / Spring Boot service on PostgreSQL that is a small but honest payments back office:
1. **Ledger:** accounts, immutable double-entry journal entries, balances that cannot drift, no overdraft on accounts that forbid it, reversal instead of edit.
2. **Payment orchestration:** create, authorize, capture, void, refund against a simulated PSP that times out, declines, double-delivers and answers late; money movements are ledger entries.
3. **Idempotency:** every mutating call takes an `Idempotency-Key`; retries (also across a crash) never create a second effect.
4. **Webhooks:** transactional outbox, signed at-least-once delivery with retries, and a receiver that de-duplicates, so the *effect* is exactly-once.
5. **Reconciliation:** match the ledger against a bank statement and report breaks (missing, extra, mismatched, duplicated).
6. **Integrity checker:** one endpoint that re-derives every invariant from the raw rows.
7. **Proof:** a harness that SIGKILLs the real service at random moments under load and checks every invariant afterwards.

## Goals
1. Invariants I1 to I8 (architecture.md) hold under concurrency, retries and crashes, and are enforced **in the database** wherever possible, not only in application code.
2. Every claim is provable by a command (`make test`, `make crash-test`, `make mutants`, `make bench`) with raw results saved.
3. Honest limits: simulator, single region, no real money, no PCI scope.
4. Free-tier deployable (Render + Neon Postgres) with the same code.

## Non-goals
Real PSP integrations, cards or UPI rails, PCI, multi-currency FX, tax, authentication beyond one API key, a UI beyond a small demo console, horizontal sharding of the ledger.

## Success metrics (targets set before building; results go in `docs/results.md`)
| Metric | Target |
|---|---|
| Crash safety | 0 broken invariants, 0 double captures, 0 lost acknowledged operations over at least 1,000 SIGKILL runs against a real Postgres |
| Concurrency | 0 invariant violations and 0 surfaced deadlocks under 64 threads of random transfers on 20 hot accounts for 60 s |
| Differential test | ledger equals a naive in-memory reference model over at least 100,000 random operations |
| Idempotency | N concurrent identical requests produce exactly 1 effect; same key with a different body is rejected |
| Injected bugs | 6 of 6 caught by the suite (`scripts/mutation_check.sh`) |
| Webhooks | 0 duplicate effects at the receiver with a 30% failure and 20% duplicate-delivery fault rate |
| Throughput | report posts/s and p50/p99 with fsync on, with hardware and method (no target) |

## Risks
| Risk | Mitigation |
|---|---|
| Scope creep into a payment gateway clone | Non-goals; the ledger and the failure proofs are the product |
| Tests that pass because they never exercise the hard paths | Fault injection, crash harness, mutation check, integrity checker verified against deliberately corrupted data |
| Heavy build toolchain (JVM, Maven, Postgres) | Maven wrapper script with checksum; tests use a local Postgres or a CI service container; no Docker needed |
| Name collision | ADR-000 |
