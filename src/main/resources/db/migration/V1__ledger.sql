-- Counterfoil ledger. Money is BIGINT minor units. Rules live in the database so that no code path,
-- including a future bug or a manual session, can break them (ADR-004, ADR-005).

CREATE TABLE accounts (
    id             BIGSERIAL PRIMARY KEY,
    code           TEXT        NOT NULL UNIQUE CHECK (code ~ '^[a-z0-9:_.\-]{1,120}$'),
    name           TEXT        NOT NULL,
    type           TEXT        NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency       CHAR(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    allow_negative BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The cached balance. Updated only by the trigger below. The CHECK is the no-overdraft rule (I8); it is
-- evaluated under the row lock taken by the UPDATE, so two concurrent debits cannot both pass.
CREATE TABLE account_balances (
    account_id     BIGINT PRIMARY KEY REFERENCES accounts (id),
    normal_side    CHAR(1) NOT NULL CHECK (normal_side IN ('D', 'C')),
    allow_negative BOOLEAN NOT NULL,
    debit_total    BIGINT  NOT NULL DEFAULT 0 CHECK (debit_total >= 0),
    credit_total   BIGINT  NOT NULL DEFAULT 0 CHECK (credit_total >= 0),
    version        BIGINT  NOT NULL DEFAULT 0,
    CONSTRAINT no_overdraft CHECK (allow_negative OR
        (CASE normal_side WHEN 'D' THEN debit_total >= credit_total ELSE credit_total >= debit_total END))
);

CREATE TABLE journal_entries (
    id                BIGSERIAL PRIMARY KEY,
    public_id         UUID        NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    kind              TEXT        NOT NULL CHECK (kind IN ('MANUAL', 'PAYMENT_CAPTURE', 'REFUND', 'PSP_SETTLEMENT', 'PAYOUT', 'REVERSAL')),
    reference         TEXT,
    description       TEXT,
    -- an entry can be reversed at most once
    reverses_entry_id BIGINT UNIQUE REFERENCES journal_entries (id),
    posted_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX journal_entries_reference_idx ON journal_entries (reference) WHERE reference IS NOT NULL;

CREATE TABLE journal_lines (
    id         BIGSERIAL PRIMARY KEY,
    entry_id   BIGINT   NOT NULL REFERENCES journal_entries (id),
    line_no    INT      NOT NULL CHECK (line_no > 0),
    account_id BIGINT   NOT NULL REFERENCES accounts (id),
    side       CHAR(1)  NOT NULL CHECK (side IN ('D', 'C')),
    amount     BIGINT   NOT NULL CHECK (amount > 0 AND amount <= 100000000000000),
    currency   CHAR(3)  NOT NULL,
    UNIQUE (entry_id, line_no),
    UNIQUE (entry_id, account_id)
);
CREATE INDEX journal_lines_account_idx ON journal_lines (account_id, id);

-- ---------------------------------------------------------------- balance cache + no overdraft
CREATE FUNCTION accounts_create_balance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO account_balances (account_id, normal_side, allow_negative)
    VALUES (NEW.id, CASE WHEN NEW.type IN ('ASSET', 'EXPENSE') THEN 'D' ELSE 'C' END, NEW.allow_negative);
    RETURN NULL;
END $$;
CREATE TRIGGER accounts_create_balance AFTER INSERT ON accounts
    FOR EACH ROW EXECUTE FUNCTION accounts_create_balance();

CREATE FUNCTION journal_lines_check_currency() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE acct_currency CHAR(3);
BEGIN
    SELECT currency INTO acct_currency FROM accounts WHERE id = NEW.account_id;
    IF acct_currency IS DISTINCT FROM NEW.currency THEN
        RAISE EXCEPTION 'line currency % does not match account % currency %', NEW.currency, NEW.account_id, acct_currency
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER journal_lines_check_currency BEFORE INSERT ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION journal_lines_check_currency();

CREATE FUNCTION journal_lines_apply_balance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE account_balances
       SET debit_total  = debit_total  + CASE WHEN NEW.side = 'D' THEN NEW.amount ELSE 0 END,
           credit_total = credit_total + CASE WHEN NEW.side = 'C' THEN NEW.amount ELSE 0 END,
           version      = version + 1
     WHERE account_id = NEW.account_id;
    RETURN NULL;
END $$;
CREATE TRIGGER journal_lines_apply_balance AFTER INSERT ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION journal_lines_apply_balance();

-- ---------------------------------------------------------------- double entry, checked at COMMIT (I1)
CREATE FUNCTION check_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    eid BIGINT;
    bad RECORD;
    n   INT;
BEGIN
    IF TG_TABLE_NAME = 'journal_entries' THEN
        eid := NEW.id;
    ELSE
        eid := NEW.entry_id;
    END IF;
    SELECT count(*) INTO n FROM journal_lines WHERE entry_id = eid;
    IF n < 2 THEN
        RAISE EXCEPTION 'journal entry % has % lines; at least 2 are required', eid, n USING ERRCODE = '23514';
    END IF;
    SELECT currency, SUM(CASE side WHEN 'D' THEN amount ELSE -amount END) AS net
      INTO bad
      FROM journal_lines WHERE entry_id = eid
     GROUP BY currency
    HAVING SUM(CASE side WHEN 'D' THEN amount ELSE -amount END) <> 0
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'journal entry % is unbalanced in %: net %', eid, bad.currency, bad.net USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_lines_balanced AFTER INSERT ON journal_lines
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();
CREATE CONSTRAINT TRIGGER journal_entries_have_lines AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_entry_balanced();

-- ---------------------------------------------------------------- append-only ledger (I6)
CREATE FUNCTION forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: the ledger is append-only (post a reversal instead)', TG_OP, TG_TABLE_NAME
        USING ERRCODE = '55000';
END $$;
CREATE TRIGGER journal_lines_append_only BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER journal_lines_no_truncate BEFORE TRUNCATE ON journal_lines
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER journal_entries_append_only BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER journal_entries_no_truncate BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER accounts_append_only BEFORE UPDATE OR DELETE ON accounts
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
