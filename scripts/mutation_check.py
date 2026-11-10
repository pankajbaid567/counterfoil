#!/usr/bin/env python3
"""Injects deliberate bugs into a scratch copy of Counterfoil and checks that the test suite (or, for crash-only bugs,
the crash harness) catches every one. A mutant that survives is a hole in the tests.

  python3 scripts/mutation_check.py            # all 7 mutants (the 2 crash-only ones take a few minutes each)
  python3 scripts/mutation_check.py --only 3   # one mutant
  python3 scripts/mutation_check.py --skip-crash

Every patch asserts that its target text exists, so a mutant can never silently become a no-op.
"""
import argparse, os, re, shutil, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRATCH = ROOT / ".mutants"


def sub(text, old, new):
    if old not in text:
        raise SystemExit(f"mutation target not found: {old[:60]!r}")
    return text.replace(old, new, 1)


def m1(r):  # lock order depends on direction (debits first) instead of one global order: opposite transfers deadlock.
    # (An earlier version only deleted the sort. It survived because lines are merged in a TreeMap by account code, which is
    # itself a global order: that mutant was equivalent, not a test hole.)
    f = r / "src/main/java/dev/counterfoil/ledger/LedgerService.java"
    f.write_text(sub(f.read_text(), "        out.sort(Comparator.comparingLong(Line::accountId));\n",
                     "        out.sort(Comparator.comparing((Line l) -> l.side));\n"))


def m2(r):  # overdraft check removed
    f = r / "src/main/resources/db/migration/V1__ledger.sql"
    t = f.read_text()
    t = sub(t, "CONSTRAINT no_overdraft CHECK (allow_negative OR\n        (CASE normal_side WHEN 'D' THEN debit_total >= credit_total ELSE credit_total >= debit_total END))",
            "CONSTRAINT no_overdraft CHECK (true)")
    f.write_text(t)


def m3(r):  # idempotency claim never inserted: every retry re-executes
    f = r / "src/main/java/dev/counterfoil/idempotency/IdempotencyService.java"
    t = f.read_text()
    i = t.index("    public Outcome runAtomic")
    j = t.index("    /** Looks up a key")
    t = t[:i] + """    public Outcome runAtomic(String scope, String key, String requestHash, Supplier<Response> work) {
        return tx.run(() -> new Outcome(work.get(), false));
    }

""" + t[j:]
    f.write_text(t)


def m4(r):  # a payment can be captured twice: state check and the database guards removed
    f = r / "src/main/java/dev/counterfoil/payments/PaymentService.java"
    f.write_text(sub(f.read_text(), 'require(p, PaymentStatus.AUTHORIZED, "capture");', "// require removed"))
    v = r / "src/main/resources/db/migration/V3__payments.sql"
    t = v.read_text()
    t = sub(t, "CREATE UNIQUE INDEX one_op_per_kind ON payment_ops (payment_id, kind) WHERE kind <> 'REFUND';", "")
    t = sub(t, "CREATE UNIQUE INDEX one_capture_entry_per_payment ON journal_entries (reference) WHERE kind = 'PAYMENT_CAPTURE';", "")
    v.write_text(t)
    s = r / "src/main/java/dev/counterfoil/payments/PaymentStatus.java"
    s.write_text(sub(s.read_text(), "case CAPTURED -> EnumSet.of(PARTIALLY_REFUNDED, REFUNDED);", "case CAPTURED -> EnumSet.of(PARTIALLY_REFUNDED, REFUNDED, CAPTURING);"))


def m5(r):  # outbox event written after the payment commits instead of inside the same transaction.
    # First run: the crash harness (3 workers x 40 kills) did NOT catch it: the window is about a millisecond. A deterministic
    # unit test (outbox write fails once) now catches it; see docs/results.md.
    f = r / "src/main/java/dev/counterfoil/payments/PaymentService.java"
    t = f.read_text()
    t = sub(t, '            outbox.enqueue("payment.created", id.toString(), snapshot(v, null));\n', "")
    t = sub(t, "        return idem.runAtomic(scope, key, requestHash, () -> {\n            ledger.ensureAccount(payable(req.merchantId())",
            "        Outcome mutated = idem.runAtomic(scope, key, requestHash, () -> {\n            ledger.ensureAccount(payable(req.merchantId())")
    t = sub(t, '            return response(201, v, "payment", id.toString());\n        });\n    }\n',
            '            return response(201, v, "payment", id.toString());\n        });\n'
            '        if (!mutated.replayed()) {\n'
            '            UUID pid = UUID.fromString(mutated.response().resourceId());\n'
            '            tx.runVoid(() -> outbox.enqueue("payment.created", pid.toString(), snapshot(view(pid), null)));\n'
            '        }\n        return mutated;\n    }\n')
    f.write_text(t)


def m6(r):  # receiver de-duplication removed: a redelivered event is applied again
    f = r / "src/main/java/dev/counterfoil/outbox/WebhookReceiver.java"
    f.write_text(sub(f.read_text(), "                    .param(\"s\", source).param(\"e\", eventId).update() == 1;", "                    .param(\"s\", source).param(\"e\", eventId).update() >= 0;"))


def m7(r):  # idempotency key claimed in its own committed transaction, effect applied in a second one (crash-only: stuck IN_PROGRESS)
    f = r / "src/main/java/dev/counterfoil/idempotency/IdempotencyService.java"
    t = f.read_text()
    i = t.index("    public Outcome runAtomic")
    j = t.index("    /** Looks up a key")
    t = t[:i] + """    public Outcome runAtomic(String scope, String key, String requestHash, Supplier<Response> work) {
        Begin b = begin(scope, key, requestHash);
        if (b instanceof Begin.Replay rp) {
            return new Outcome(rp.response(), true);
        }
        return tx.run(() -> {
            Response resp = work.get();
            completeInTx(scope, key, resp);
            return new Outcome(resp, false);
        });
    }

""" + t[j:]
    f.write_text(t)


# (name, patch, how it must be caught, maven test selector or None for the crash harness)
MUTANTS = [
    ("lines not sorted by account id", m1, "LedgerConcurrencyTest"),
    ("overdraft check removed", m2, "LedgerSchemaTest,LedgerServiceTest,PaymentServiceTest"),
    ("idempotency claim never inserted", m3, "IdempotencyTest,PaymentServiceTest"),
    ("double-capture guards removed", m4, "PaymentServiceTest"),
    ("outbox event outside the transaction", m5, "PaymentServiceTest"),
    ("receiver de-duplication removed", m6, "WebhookTest"),
    ("idempotency key claimed in a separate transaction", m7, None),
]


def sh(cmd, cwd, timeout):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", type=int)
    ap.add_argument("--skip-crash", action="store_true")
    ap.add_argument("--force-crash", action="store_true", help="judge the mutant by the crash harness even if unit tests can catch it")
    ap.add_argument("--crash-cycles", type=int, default=40)
    args = ap.parse_args()
    results = []
    for n, (name, patch, tests) in enumerate(MUTANTS, 1):
        if args.only and args.only != n:
            continue
        if tests is None and args.skip_crash:
            continue
        if SCRATCH.exists():
            shutil.rmtree(SCRATCH)
        SCRATCH.mkdir()
        for item in ["src", "pom.xml", "mvnw", ".mvn", ".tools", "tools"]:
            s = ROOT / item
            if s.exists():
                (shutil.copytree if s.is_dir() else shutil.copy2)(s, SCRATCH / item, **({"ignore": shutil.ignore_patterns("results")} if item == "tools" else {}))
        patch(SCRATCH)
        t0 = time.time()
        if tests and not args.force_crash:
            p = sh(["./mvnw", "-q", "test", f"-Dtest={tests}"], SCRATCH, 1200)
            caught = p.returncode != 0
            how = "tests failed: " + ", ".join(sorted(set(re.findall(r"(\w+Test)\.\w+", p.stdout + p.stderr)))[:3])
        else:
            b = sh(["./mvnw", "-q", "package", "-DskipTests"], SCRATCH, 600)
            if b.returncode != 0:
                print(b.stdout[-2000:], b.stderr[-2000:])
                raise SystemExit(f"mutant {n} does not compile")
            out = SCRATCH / "results"
            p = sh(["java", "tools/crashtest/CrashTest.java", "--workers", "3", "--cycles", str(args.crash_cycles), "--out", str(out), "--jar", "target/counterfoil.jar"], SCRATCH, 3600)
            caught = p.returncode != 0
            first = next((l.strip() for l in p.stdout.splitlines() if l.startswith("  worker") or l.startswith("VIOLATION")), "")
            how = "crash harness: " + first[:140]
        results.append((n, name, caught, how, time.time() - t0))
        print(f"mutant {n}: {name}: {'CAUGHT' if caught else 'SURVIVED'} ({time.time() - t0:.0f}s) {how}", flush=True)
    if SCRATCH.exists():
        shutil.rmtree(SCRATCH)
    caught = sum(1 for r in results if r[2])
    print(f"\n{caught}/{len(results)} mutants caught")
    sys.exit(0 if caught == len(results) else 1)


if __name__ == "__main__":
    main()
