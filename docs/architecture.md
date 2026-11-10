# Architecture

```
 client ──HTTP──► Spring MVC (API key, Idempotency-Key, validation)
                      │
        ┌─────────────┼───────────────────────────────┐
        ▼             ▼                               ▼
   LedgerService  PaymentService ──► PspSimulator   ReconciliationService
   (post, reverse)  (state machine)   (faults, own committed state)
        │             │  ▲
        │             │  └── RecoverySweeper (finishes interrupted payments)
        ▼             ▼
   PostgreSQL: accounts, balances, journal_entries, journal_lines, payments,
               idempotency_keys, outbox, inbound_events, psp_transactions, recon_*
        ▲
        └── WebhookDispatcher (outbox, SKIP LOCKED, signed, retried) ──► merchant endpoint
```

## Money and accounts
Amounts are `BIGINT` minor units (paise) plus a 3-letter currency; there are no floats and no `BigDecimal` in storage. Accounts have a type (ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE), hence a *normal side* (debit for ASSET/EXPENSE, credit otherwise), a currency, and `allow_negative`.

Chart of accounts used by the payment flows:
| code | type | meaning |
|---|---|---|
| `psp_clearing` | ASSET | what the PSP owes us for captured payments |
| `merchant:{id}:payable` | LIABILITY | what we owe a merchant (`allow_negative = false`) |
| `fees:revenue` | REVENUE | our fee income |
| `bank:settlement` | ASSET | our bank balance for settlements |

Postings: **capture** of amount A with fee F: Dr `psp_clearing` A / Cr `merchant:m:payable` A-F / Cr `fees:revenue` F. **Refund** R (reserve, then complete): Dr `merchant:m:payable` (merchant share) and Dr `fees:revenue` (our share) / Cr `refunds:pending` R; on PSP approval Dr `refunds:pending` / Cr `psp_clearing`; on decline the reservation is reversed. The merchant share is floor(cumulative refund x (amount - fee) / amount) minus what earlier refunds already took. **PSP settlement** S (inbound webhook): Dr `bank:settlement` S / Cr `psp_clearing` S. **Payout** P: Dr `merchant:m:payable` P / Cr `bank:settlement` P. Authorization and void do not touch the ledger.

## Schema (excerpt; full DDL in `src/main/resources/db/migration`)
```sql
accounts(id, code UNIQUE, name, type, currency, allow_negative)
account_balances(account_id PK, debit_total, credit_total, version,
   normal_side, allow_negative,
   CHECK (allow_negative OR natural_balance >= 0))        -- no overdraft, enforced under the row lock
journal_entries(id, public_id UNIQUE, kind, reference, description,
   reverses_entry_id UNIQUE REFERENCES journal_entries,   -- an entry can be reversed at most once
   posted_at)
journal_lines(id, entry_id, line_no, account_id, side 'D'|'C', amount > 0, currency, UNIQUE(entry_id, line_no))
-- triggers:
--  * deferred CONSTRAINT trigger on journal_lines: at COMMIT every entry balances per currency
--  * BEFORE INSERT on journal_lines: line currency = account currency
--  * AFTER INSERT on journal_lines: update account_balances (so the cache cannot be skipped)
--  * BEFORE UPDATE/DELETE/TRUNCATE on entries and lines: raise (immutability)
payments(id, merchant_id, amount, currency, status, fee, psp_reference, captured_amount, refunded_amount, version, ...)
payment_events(payment_id, seq, from_status, to_status, at)         -- audit trail
idempotency_keys(scope, key, request_hash, state IN_PROGRESS|COMPLETED, response_status, response_body, resource_type, resource_id)
outbox(id, event_id UUID UNIQUE, type, aggregate_id, payload JSONB, status, attempts, next_attempt_at, locked_until, delivered_at)
inbound_events(event_id PK, source, received_at)                    -- receiver-side de-duplication
psp_transactions(psp_reference PK, kind, amount, outcome, ...)      -- the "remote" system's own state
recon_runs / recon_items                                            -- reconciliation results
```

## Invariants (all checked by `GET /v1/admin/invariants`, which re-derives them from raw rows)
| # | Invariant | Enforced by |
|---|---|---|
| I1 | every journal entry balances per currency | deferred constraint trigger (DB) + service validation |
| I2 | `account_balances` equals the sum of the account's lines | trigger that maintains it + checker |
| I3 | total debits equal total credits (trial balance), per currency | follows from I1; checked |
| I4 | a payment is captured at most once; refunds never exceed the captured amount; ledger postings match payment totals | state machine + `CHECK` + unique `(payment, kind)` reference + checker |
| I5 | same `Idempotency-Key` + same body gives the same response and exactly one effect; same key + different body is rejected | idempotency table, unique `(scope, key)` |
| I6 | entries and lines are immutable | DB triggers |
| I7 | every committed state change has exactly one outbox event; the receiver applies each event once | outbox row in the same transaction; `inbound_events` primary key |
| I8 | accounts with `allow_negative = false` never go below zero, even under races | `CHECK` on `account_balances` under the row lock |

## Concurrency
Isolation is READ COMMITTED with explicit locks (ADR-011). Lines are inserted **sorted by account id**, so the balance-maintaining trigger takes row locks in one global order and concurrent postings cannot deadlock; the service still retries SQLSTATE `40P01`/`40001` a bounded number of times and counts them.

## Idempotency flows
- *Ledger-only calls* (`POST /v1/journal-entries`, reversals): the idempotency row is inserted **in the same transaction** as the business rows, so there is no half state: a crash leaves nothing; a concurrent duplicate blocks on the unique index until the first commits, then replays its stored response.
- *PSP-backed calls* (authorize, capture, refund): two phases. (1) Transaction A commits the intent (`payments` status `CAPTURING`, idempotency row `IN_PROGRESS`). (2) The PSP is called with a PSP reference derived from our payment id, so the call is idempotent on the PSP side too. (3) Transaction B applies the result: ledger entry, status, outbox event, idempotency row `COMPLETED`, atomically. A crash between 1 and 3 leaves `CAPTURING`; the **recovery sweeper** asks the PSP what happened (lookup by reference) and finishes the job. A retry that arrives meanwhile gets `409 in progress` or, once finished, the stored response.

## Webhooks
Outbound: the event row is written in the business transaction (transactional outbox). The dispatcher claims due rows with `FOR UPDATE SKIP LOCKED` and a short lease, sends `POST` with `Counterfoil-Event-Id` and `Counterfoil-Signature: t=<unix>,v1=<hex HMAC-SHA256 of "t.body">`, marks `DELIVERED` on 2xx, otherwise retries with exponential backoff and jitter, and parks the row as `DEAD` after a limit. Delivery is **at-least-once**; the reference receiver verifies the signature and timestamp window and de-duplicates on the event id, which makes the effect exactly-once. Inbound PSP webhooks (`psp.settlement.paid`) use the same de-duplication table.

## Reconciliation
Input: statement rows `(reference, direction, amount, value_date)`. Match each ledger line on `bank:settlement` to a statement row by `(reference, amount, direction)`; report **matched**, **missing in bank**, **missing in ledger**, **amount mismatch** (same reference, different amount), and **duplicates** (a reference appearing more than once on either side). Results are stored and idempotent per statement hash.

## Failure model of the PSP simulator
Per call, configurable probabilities: success, decline, timeout *after the PSP applied the operation* (the dangerous case), timeout before it applied it, duplicate and delayed inbound webhooks. A seed makes runs reproducible.
