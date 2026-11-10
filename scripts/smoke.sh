#!/usr/bin/env bash
# End-to-end smoke test of a running Counterfoil over HTTP: payment lifecycle, idempotent replay, refund, integrity check.
#   URL=https://counterfoil-demo-m5wl.onrender.com KEY=<your COUNTERFOIL_API_KEY> bash scripts/smoke.sh
# The key is read from the environment and is never written to disk. It creates one payment for merchant "smoke-<random>".
set -u
: "${URL:?set URL}"; : "${KEY:?set KEY}"
M="smoke-$(date +%s)"
fail=0
j() { python3 -c "import sys,json; d=json.load(sys.stdin); print(d$1)" 2>/dev/null; }
req() { curl -s --max-time 90 -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" "$@"; }
check() { if [ "$2" = "$3" ]; then echo "PASS  $1 = $2"; else echo "FAIL  $1: got '$2', expected '$3'"; fail=1; fi; }

echo "waking the service (free tier can take up to a minute)..."
check "health" "$(curl -s --max-time 120 "$URL/actuator/health" | j "['status']")" "UP"
check "no key is refused (HTTP)" "$(curl -s -o /dev/null -w '%{http_code}' "$URL/v1/trial-balance")" "401"

P=$(req -X POST "$URL/v1/payments" -H "Idempotency-Key: $M-create" -d "{\"merchant_id\":\"$M\",\"amount\":10000,\"currency\":\"INR\"}")
ID=$(echo "$P" | j "['id']")
check "payment created" "$(echo "$P" | j "['status']")" "CREATED"
check "authorize" "$(req -X POST "$URL/v1/payments/$ID/authorize" -H "Idempotency-Key: $M-auth" | j "['status']")" "AUTHORIZED"
C=$(req -X POST "$URL/v1/payments/$ID/capture" -H "Idempotency-Key: $M-cap")
check "capture" "$(echo "$C" | j "['status']")" "CAPTURED"
check "fee (2% of 10000)" "$(echo "$C" | j "['fee']")" "200"
check "merchant payable after capture" "$(req "$URL/v1/accounts/merchant:$M:payable/balance" | j "['natural']")" "9800"

R=$(req -i -X POST "$URL/v1/payments/$ID/capture" -H "Idempotency-Key: $M-cap")
check "capture retried with the same key is a replay" "$(echo "$R" | tr -d '\r' | grep -ci '^idempotent-replayed: true')" "1"
check "replay did not capture again" "$(req "$URL/v1/accounts/merchant:$M:payable/balance" | j "['natural']")" "9800"
check "a different key cannot capture twice (HTTP)" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$URL/v1/payments/$ID/capture" -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $M-cap2")" "409"

check "partial refund" "$(req -X POST "$URL/v1/payments/$ID/refunds" -H "Idempotency-Key: $M-ref" -d '{"amount":3000}' | j "['status']")" "PARTIALLY_REFUNDED"
check "merchant payable after refund (9800 - 2940)" "$(req "$URL/v1/accounts/merchant:$M:payable/balance" | j "['natural']")" "6860"

check "integrity checker ok" "$(req "$URL/v1/admin/invariants" | j "['ok']")" "True"
check "trial balance balanced" "$(req "$URL/v1/trial-balance" | j "['balanced']")" "True"
[ $fail -eq 0 ] && echo "ALL CHECKS PASSED" || { echo "SOME CHECKS FAILED"; exit 1; }
