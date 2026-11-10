# DESIGN.md: decisions (short ADRs)

## ADR-000: Name
**Accepted.** *Counterfoil* (the stub that proves a payment happened). GitHub search 2026-11-10: 13 repos with this name, one an unrelated 0-star reconciliation experiment. Rejected: `ledgerline` (524 repos), `tallybook` (162), `trueup` (a jobs site), `dualentry` (an accounting company).

## ADR-001: A simulator with a realistic failure model
**Accepted.** No real money, PSPs, cards or UPI. The product is the correctness machinery (ledger, idempotency, outbox, reconciliation) and the proof that it survives retries, races and crashes.

## ADR-002: Java 21 target, Spring Boot 3.5, JdbcTemplate, Flyway
**Accepted.** Java and Spring Boot are what the target JDs ask for. The language level is 21 (records, sealed types, pattern switch, virtual threads) and the project is built and tested on JDK 25. **Plain SQL through `JdbcTemplate`, not JPA/Hibernate:** locking, triggers and `SKIP LOCKED` are the point, and they must be visible in the code. Flyway versions the schema. Spring Boot 4.x was available but 3.5 is the version whose starters and behaviour are well known; moving up is a later chore.

## ADR-003: Money is BIGINT minor units plus a currency
**Accepted.** No floating point anywhere; amounts must be positive and bounded; mixed-currency entries are allowed only if each currency balances on its own.

## ADR-004: Double entry, immutable, balance enforced by the database
**Accepted.** An entry is a set of lines whose debits equal credits. The rule is enforced by a **deferred constraint trigger at COMMIT**, so no code path (including a future bug or a manual `psql` session) can commit an unbalanced entry. Entries and lines are immutable (triggers reject UPDATE, DELETE and TRUNCATE); mistakes are fixed by a *reversal entry*, linked by a unique `reverses_entry_id`, so an entry can be reversed once.

## ADR-005: Balance cache maintained by trigger, no-overdraft by CHECK, locks in one order
**Accepted.** Reading a balance by summing lines does not scale and locking by hand is error-prone. `account_balances` is updated by an AFTER INSERT trigger on lines (it cannot be forgotten by a writer), and a `CHECK` on that table enforces "no overdraft" for accounts that forbid it: the check runs under the row lock, so two concurrent debits cannot both pass. The service inserts lines **sorted by account id** so row locks are always taken in the same order. Deadlock (`40P01`) and serialization (`40001`) failures are retried a bounded number of times and counted. `account_balances == SUM(lines)` is verified by the integrity checker (I2).

## ADR-006: Idempotency inside the transaction where possible, two-phase with recovery where not
**Accepted.** See architecture.md. For ledger-only calls the key row commits atomically with the business rows. For PSP-backed calls the external call cannot sit inside a database transaction, so intent and result are separate transactions and a **recovery sweeper** resolves interrupted operations by querying the PSP by reference. The request body hash is stored; the same key with a different body is `422`, never a silent replay.

## ADR-007: Payment state machine and chart of accounts
**Accepted.** States CREATED, AUTHORIZED, CAPTURING, CAPTURED, PARTIALLY_REFUNDED, REFUNDED, VOIDED, FAILED, with legal transitions in one place and a property test over random event sequences. Authorization and void do not touch the ledger; capture, refund, settlement and payout do. Fees are refunded proportionally: the merchant bears floor(cumulative_refund * (amount - fee) / amount) and fee revenue gives back the rest, so a full refund leaves both at exactly zero whatever the sequence of partial refunds (found by the first full-refund test; the original "fees are not refunded" rule made a full refund impossible).

## ADR-008: Transactional outbox, signed at-least-once webhooks, receiver de-duplication
**Accepted.** Writing the event in the same transaction as the state change removes the "committed but never announced" and "announced but rolled back" failures. Delivery is at-least-once because "sent but not marked" cannot be avoided; exactly-once *effect* comes from de-duplication on a stable event id at the receiver. Claiming uses `FOR UPDATE SKIP LOCKED` with a lease so several instances can run.

## ADR-009: A PSP simulator with its own committed state and fault injection
**Accepted.** The simulator keeps its state in its own table, committed independently, exactly like a remote system. That makes the dangerous case reproducible: the PSP applied the capture but our process died before recording it.

## ADR-010: Reconciliation is a deterministic matching algorithm with explicit categories
**Accepted.** Both sides are reduced to one signed amount per reference (positive = into the bank) for a period, then compared. Findings: `MISSING_IN_BANK`, `MISSING_IN_LEDGER`, `AMOUNT_MISMATCH`, `DUPLICATE_IN_BANK`; everything else counts as matched. Results are stored and a run is idempotent per statement hash (`UNIQUE(statement_hash)`; concurrent submissions of one statement produce exactly one run). Findings are reported, never auto-fixed.
**Assumption, stated:** a statement line's `reference` equals the ledger entry's `reference` (`psp-settlement:...`, `payout:...`). A real bank has its own references, so a real system needs a mapping step in front of this; it is out of scope for the simulator.

## ADR-011: READ COMMITTED with explicit locks, not SERIALIZABLE
**Accepted.** Serializable isolation would make correctness automatic but produces retry storms on hot accounts. Here every cross-row invariant is protected by either a database constraint, a unique index, or a row lock taken in a fixed order, and the tests hammer exactly those. Rejected alternatives are listed so the choice is a decision, not an accident.

## ADR-012: The integrity checker re-derives everything from raw rows
**Accepted.** `GET /v1/admin/invariants` recomputes I1 to I4 and I7 (payment checks live in `PaymentInvariants`) from `journal_lines`, `payments` and `outbox`, not from caches. It is what the crash harness calls, and its own tests corrupt data on purpose (with triggers disabled in a test session) to prove it notices.

## ADR-013: One API key, no user system
**Accepted.** Constant-time comparison of a bearer key from the environment. Real authentication is out of scope.

## ADR-014: Real PostgreSQL in tests, never H2
**Accepted.** Triggers, deferred constraints, `SKIP LOCKED` and lock behaviour differ between engines, so only PostgreSQL proves anything. Tests use a throwaway local cluster (`initdb` in a temp directory, no Docker) or a CI service container selected by environment variable.
