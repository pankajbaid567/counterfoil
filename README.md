# Counterfoil

**A double-entry ledger and payment back office that stays correct when requests are retried, webhooks arrive twice and the process is killed mid-payment.** Java 21, Spring Boot 3.5, PostgreSQL. It is a simulator: no real money and no real payment provider (the PSP is a simulator with injectable faults).

The counterfoil is the stub you keep to prove a payment happened. The product here is the proof, not the CRUD.

## What was measured
Full detail, method and limits: [docs/results.md](docs/results.md). Laptop (Apple M3), busy machine, one box for client, app and database.

- **1,000 SIGKILL runs** of the real JVM under load (payments with injected PSP faults, transfers, refunds, webhooks): **0 lost acknowledged writes, 0 duplicate effects, invariants intact**. 4.67 million acknowledged facts re-checked after restarts.
- **7 of 7 injected bugs caught** by the tests and the crash harness (one of them by the harness alone). One mutant first survived because it was equivalent, and one survived the harness because its window is about a millisecond; both are explained in the results.
- **100,000-operation differential test** against a plain reference ledger; **64 concurrent writers, 668,275 postings, 0 deadlock retries**.
- About **6,400 ledger postings/s** (p99 under 12 ms) and about **725 complete payments/s** over HTTP with fsync on, closed loop.

## What it does
- **Ledger.** Accounts, immutable journal entries and lines in integer minor units. The database rejects unbalanced entries (deferred constraint trigger), overdrafts on guarded accounts (CHECK), edits and deletes of history (triggers), and a second reversal of the same entry (unique index). A trigger-maintained balance cache is re-derived from raw lines by `GET /v1/admin/invariants`.
- **Idempotency.** Every mutating call takes an `Idempotency-Key`. A retry replays the stored response; the same key with a different body is refused; concurrent duplicates produce one effect.
- **Payments.** `create, authorize, capture, void, refund` with an explicit state machine. The PSP is called outside any database transaction; an interrupted or timed-out operation stays `PENDING` and is finished by a retry or a recovery sweeper, so the PSP is applied once and the ledger once. Refunds reserve the money before the PSP is called and release it on decline.
- **Webhooks.** Transactional outbox, HMAC-signed (`Counterfoil-Signature: t=...,v1=...`), `SKIP LOCKED` dispatchers with lease and backoff, DEAD after a limit; a reference receiver de-duplicates on a primary key. An inbound signed `psp.settlement.paid` event is applied once.
- **Reconciliation.** A bank statement is compared with the ledger's bank account for a period: matched, missing in bank, missing in ledger, amount mismatch, duplicate in bank. A run is idempotent per statement.

## Run it
Needs JDK 21+ and PostgreSQL. No other install: `./mvnw` downloads a checksum-verified Maven into `.tools/`.

```bash
./mvnw test                              # 75 tests; starts a throwaway local PostgreSQL if pg binaries are on PATH or PG_BIN is set
./mvnw package -DskipTests
export DATABASE_URL=jdbc:postgresql://localhost:5432/counterfoil DATABASE_USER=counterfoil DATABASE_PASSWORD=...
export COUNTERFOIL_API_KEY=$(openssl rand -hex 16)   # or COUNTERFOIL_ALLOW_NO_AUTH=true for local development only
java -jar target/counterfoil.jar
```

```bash
curl -s -X POST localhost:8080/v1/payments -H "Authorization: Bearer $COUNTERFOIL_API_KEY" -H "Idempotency-Key: demo-1" \
  -H "Content-Type: application/json" -d '{"merchant_id":"shop1","amount":10000,"currency":"INR"}'
```

## API (`/v1`, JSON in snake_case, Bearer API key)
| | |
|---|---|
| `POST /accounts`, `GET /accounts/{code}/balance`, `GET /accounts/{code}/statement` | chart of accounts |
| `POST /journal-entries`, `GET /journal-entries/{id}`, `POST /journal-entries/{id}/reverse` | manual entries |
| `POST /payments`, `GET /payments/{id}`, `POST /payments/{id}/authorize \| capture \| void \| refunds` | payments |
| `POST /merchants/{id}/payouts` | pay out what a merchant is owed |
| `POST /webhooks/psp` | inbound PSP event, authenticated by signature, not by key |
| `POST /reconciliations`, `GET /reconciliations/{id}` | bank reconciliation |
| `GET /trial-balance`, `GET /admin/invariants` | integrity |

Errors always look like `{"error":{"code":"...","message":"..."}}`.

## Reproduce the proofs
```bash
./mvnw package -DskipTests
java tools/crashtest/CrashTest.java --workers 5 --cycles 200     # 1,000 runs, about 95 minutes
python3 scripts/mutation_check.py                                 # 7 mutants
java tools/bench/Bench.java                                       # throughput and latency
```

## Layout
`src/main/java/dev/counterfoil/{ledger,idempotency,payments,outbox,api,admin}`, `src/main/resources/db/migration` (V1 to V5), `tools/crashtest`, `tools/bench`, `scripts/mutation_check.py`, `docs/`.

## Docs
[PRD](docs/prd.md), [architecture and invariants](docs/architecture.md), [design decisions (ADR-000 to ADR-014)](docs/DESIGN.md), [testing](docs/testing.md), [results](docs/results.md), [resume claims register](docs/resume-claims.md), [development plan](docs/development_plan.md).

## What this is not
Not a payment gateway: no real PSP, no cards, no PCI scope, no UPI rails. One currency (INR), one API key, no user system, a single database. PostgreSQL's own crash safety is assumed, not tested. Not deployed yet: the Dockerfile, `render.yaml` and CI workflow are written but unrun (no Docker daemon here, nothing pushed to GitHub).
