package dev.counterfoil;

import static org.assertj.core.api.Assertions.assertThat;

import dev.counterfoil.ledger.*;
import dev.counterfoil.ledger.Records.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The ledger against a deliberately naive in-memory model, over a long random sequence of postings and
 * reversals: every operation must have the same outcome and every balance must match at the end.
 */
class LedgerDifferentialTest {
    enum Outcome { OK, INSUFFICIENT, ALREADY_REVERSED, INVALID }

    static class Model {
        final Map<String, Long> natural = new HashMap<>();
        final Map<String, Side> normal = new HashMap<>();
        final Set<String> guarded = new HashSet<>();
        final List<List<LineRequest>> entries = new ArrayList<>();
        final List<Boolean> isReversal = new ArrayList<>();
        final Set<Integer> reversed = new HashSet<>();

        Outcome apply(List<LineRequest> lines, boolean reversal) {
            Map<String, Long> next = new HashMap<>(natural);
            for (LineRequest l : lines) {
                long delta = l.side() == normal.get(l.account()) ? l.amount() : -l.amount();
                next.merge(l.account(), delta, Long::sum);
            }
            for (String g : guarded) {
                if (next.get(g) < 0) return Outcome.INSUFFICIENT;
            }
            natural.putAll(next);
            entries.add(lines);
            isReversal.add(reversal);
            return Outcome.OK;
        }

        Outcome reverse(int idx) {
            if (isReversal.get(idx)) return Outcome.INVALID;
            if (reversed.contains(idx)) return Outcome.ALREADY_REVERSED;
            List<LineRequest> flipped = entries.get(idx).stream().map(l -> new LineRequest(l.account(), l.side().opposite(), l.amount())).toList();
            Outcome o = apply(flipped, true);
            if (o == Outcome.OK) reversed.add(idx);
            return o;
        }
    }

    @Test
    void ledgerMatchesTheReferenceModel() {
        TestEnv env = new TestEnv("diff", 8);
        int n = TestEnv.longRun() ? 100_000 : 3_000;
        Random r = new Random(20261110);
        Model m = new Model();
        List<String> codes = new ArrayList<>();
        AccountType[] types = {AccountType.ASSET, AccountType.LIABILITY, AccountType.EQUITY, AccountType.EXPENSE, AccountType.REVENUE};
        for (int i = 0; i < 9; i++) {
            AccountType t = types[i % types.length];
            boolean guarded = i % 3 == 1;                                  // a third of the accounts forbid a negative balance
            String code = "d" + i;
            env.ledger.createAccount(code, null, t, "INR", !guarded);
            codes.add(code);
            m.normal.put(code, t.normalSide());
            m.natural.put(code, 0L);
            if (guarded) m.guarded.add(code);
        }
        List<UUID> publicIds = new ArrayList<>();          // index-aligned with the model's entries
        Map<Outcome, Integer> seen = new EnumMap<>(Outcome.class);
        for (int op = 0; op < n; op++) {
            Outcome want, got;
            if (!publicIds.isEmpty() && r.nextInt(100) < 20) {
                int idx = r.nextInt(publicIds.size());
                want = m.reverse(idx);
                try {
                    EntryView v = env.ledger.reverse(publicIds.get(idx), null);
                    got = Outcome.OK;
                    publicIds.add(v.publicId());
                } catch (InsufficientFundsException e) { got = Outcome.INSUFFICIENT; }
                catch (AlreadyReversedException e) { got = Outcome.ALREADY_REVERSED; }
                catch (InvalidEntryException e) { got = Outcome.INVALID; }
            } else {
                List<LineRequest> lines = randomBalancedEntry(r, codes);
                want = m.apply(lines, false);
                try {
                    publicIds.add(env.ledger.post(new PostRequest(null, null, lines)).publicId());
                    got = Outcome.OK;
                } catch (InsufficientFundsException e) { got = Outcome.INSUFFICIENT; }
            }
            assertThat(got).as("operation %d", op).isEqualTo(want);
            seen.merge(got, 1, Integer::sum);
        }
        for (String c : codes) {
            assertThat(env.ledger.balance(c).natural()).as("balance of %s", c).isEqualTo(m.natural.get(c));
        }
        env.assertInvariants();
        assertThat(seen.get(Outcome.OK)).isGreaterThan(n / 4);
        assertThat(seen.getOrDefault(Outcome.INSUFFICIENT, 0)).as("the model must exercise overdraft refusals").isGreaterThan(0);
        assertThat(seen.getOrDefault(Outcome.ALREADY_REVERSED, 0)).as("and double reversals").isGreaterThan(0);
        System.out.println("differential: " + n + " operations, outcomes " + seen);
    }

    static List<LineRequest> randomBalancedEntry(Random r, List<String> codes) {
        int k = 2 + r.nextInt(3);
        List<String> pick = new ArrayList<>(codes);
        Collections.shuffle(pick, r);
        List<LineRequest> lines = new ArrayList<>();
        long net = 0;                                     // debits minus credits so far
        for (int i = 0; i < k - 1; i++) {
            Side s = r.nextBoolean() ? Side.D : Side.C;
            long amt = 1 + r.nextInt(300);
            lines.add(new LineRequest(pick.get(i), s, amt));
            net += s == Side.D ? amt : -amt;
        }
        if (net == 0) {                                   // balanced already: add an offsetting pair on one more account is impossible, so flip a side
            LineRequest last = lines.remove(lines.size() - 1);
            lines.add(new LineRequest(last.account(), last.side(), last.amount()));
            lines.add(new LineRequest(pick.get(k - 1), net >= 0 ? Side.C : Side.D, 1));
            lines.add(new LineRequest(pick.get(k), net >= 0 ? Side.D : Side.C, 1));
            return lines;
        }
        lines.add(new LineRequest(pick.get(k - 1), net > 0 ? Side.C : Side.D, Math.abs(net)));
        return lines;
    }
}
