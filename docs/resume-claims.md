# Resume claims register

A claim may be used only if the command beside it still reproduces it. Numbers are from `docs/results.md`.

| Claim | Proof command | Status |
|---|---|---|
| Double-entry ledger in PostgreSQL where the database itself rejects unbalanced entries, overdrafts and edits to history | `./mvnw test -Dtest=LedgerSchemaTest` | verified |
| Ledger matches a naive reference model over 100,000 random operations | `COUNTERFOIL_LONG=1 ./mvnw test -Dtest=LedgerDifferentialTest` | verified |
| 64 concurrent writers, 668,275 postings in 60 s, 0 deadlock retries (in-process) | `COUNTERFOIL_LONG=1 ./mvnw test -Dtest=LedgerConcurrencyTest` | verified |
| Idempotent payment API: retries and concurrent duplicates produce one effect; PSP timeouts recovered exactly once | `./mvnw test -Dtest=PaymentServiceTest,IdempotencyTest` | verified |
| Survived 1,000 SIGKILL runs under load with 0 lost acknowledged writes, 0 duplicate effects, invariants intact | `java tools/crashtest/CrashTest.java --workers 5 --cycles 200` (about 95 min) | verified once, 2026-11-10 |
| Test suite catches 7 of 7 injected bugs | `python3 scripts/mutation_check.py` | verified |
| About 6,400 ledger postings per second at p99 under 12 ms with fsync on, on a laptop, closed loop | `java tools/bench/Bench.java` | verified, busy laptop |
| Signed at-least-once webhooks with receiver de-duplication | `./mvnw test -Dtest=WebhookTest` | verified |
| Reconciliation against a bank statement with 4 break categories | `./mvnw test -Dtest=ReconciliationTest` | verified |

Do not claim: production readiness, real PSP integration, PCI compliance, multi-region or high availability, that PostgreSQL crash safety was tested, or anything about the hosted demo (not deployed).
