# Development plan

## Phase 0: plan (this folder)
- [x] Name check, PRD, architecture and invariants, ADRs, test strategy

## Phase 1: toolchain and ledger core
- [x] Maven (checksum-verified, project-local), Spring Boot skeleton, Flyway, local Postgres harness for tests
- [x] Schema with triggers; `Money`, accounts, `LedgerService` (post, reverse, statement, trial balance)
- [x] Database, concurrency and differential tests

## Phase 2: idempotency and integrity
- [x] Idempotency (single-transaction form), request hashing, replay
- [x] Integrity checker endpoint and its corruption tests

## Phase 3: payments
- [x] State machine, PSP simulator with faults, two-phase flows, recovery sweeper, refunds, payouts
- [x] Outbox, dispatcher, signed webhooks, reference receiver, inbound PSP webhook

## Phase 4: reconciliation and API
- [x] Reconciliation service and endpoints; REST controllers, API key, error model (OpenAPI spec not written: the endpoint list in the README is the contract)

## Phase 5: proofs
- [x] Crash harness written (`tools/crashtest/CrashTest.java`)
- [x] Crash campaign of 1,000 runs, 0 violations, recorded in `docs/results.md`
- [x] Mutation check: 7 of 7 caught (`scripts/mutation_check.py`); two survivals along the way explained in `docs/results.md`
- [x] Throughput benchmark (`tools/bench/Bench.java`)

## Phase 6: ship
- [x] Dockerfile, `render.yaml`, CI workflow written (not yet run: no Docker daemon here, no GitHub push)
- [x] README, `docs/results.md`, resume claims register
- [ ] Deploy: Render + Neon (needs the owner's GitHub push and Neon DSN)
