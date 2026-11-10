# Testing strategy

| Layer | Proves |
|---|---|
| Unit | `Money` arithmetic and bounds; entry validation; payment state machine (legal transitions only, property test over random event sequences); signature creation and verification; backoff schedule |
| Database | the deferred balance trigger rejects an unbalanced entry at commit; line currency must match the account; immutability triggers reject UPDATE/DELETE/TRUNCATE; the no-overdraft CHECK fires under concurrent debits; an entry can be reversed once |
| Ledger integration | post, reverse, statement, trial balance; sorted-lock ordering avoids deadlock; deadlock retry works when forced |
| **Concurrency** | 64 threads of random transfers over 20 hot accounts for 60 s: all invariants hold, money is conserved, no deadlock surfaces |
| **Differential** | the ledger equals a naive in-memory model over 100,000 random operations (post, reverse, rejected overdrafts) |
| Idempotency | N concurrent identical requests, 1 effect; same key + different body is 422; replay returns the stored response including after a restart |
| Payments | full flows with each PSP fault: decline, timeout before apply, timeout *after* apply (recovered by the sweeper), duplicate and delayed webhooks; refund limits; double capture impossible |
| Webhooks | flaky receiver (30% failures, 20% duplicates): every event delivered at least once, the receiver's effect applied exactly once; bad signature and stale timestamp rejected; dead-letter after the retry limit |
| Reconciliation | each of the five outcomes; idempotent per statement hash |
| Integrity checker | clean data passes; data corrupted on purpose (triggers disabled in a test session) is detected for I1, I2, I4, I7 |
| **Crash harness** | `tools/crashtest`: real JVM, real Postgres, concurrent clients with retries, SIGKILL at random moments, restart, then the integrity checker, every acknowledged operation present, no double effect, in-flight requests retried with the same key |
| **Mutation check** | `scripts/mutation_check.sh`: injects 6 bugs (lines not sorted by account, balance check skipped, idempotency insert removed, double-capture guard removed, outbox written outside the transaction, receiver de-duplication removed) and requires the suite to fail for each |

Limits: SIGKILL kills the application process only, not PostgreSQL and not the machine; the PSP is a simulator; throughput numbers are one laptop with a local Postgres.
