#!/usr/bin/env bash
# End-to-end smoke test of the running stack through its public APIs only.
# Creates two fresh users (unique suffix), tops up, transfers, and checks every step.
#
# Usage:   ./scripts/smoke-test.sh
# Env:     AUTH_URL WALLET_URL PAYMENT_URL NOTIFICATION_URL   (defaults: http://localhost:8081 / 8082 / 8083 / 8086)
# Needs:   curl, jq
# Exit:    0 = all checks passed, 1 = at least one failed
set -uo pipefail

AUTH_URL="${AUTH_URL:-http://localhost:8081}"
WALLET_URL="${WALLET_URL:-http://localhost:8082}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8083}"
NOTIFICATION_URL="${NOTIFICATION_URL:-http://localhost:8086}"

for bin in curl jq; do
  command -v "$bin" >/dev/null || { echo "Missing dependency: $bin" >&2; exit 1; }
done

PASS=0; FAIL=0
RUN_ID="$(date +%s)$RANDOM"
BODY="$(mktemp)"; trap 'rm -f "$BODY"' EXIT

ok()    { echo "  ✔ $1"; PASS=$((PASS + 1)); }
ko()    { echo "  ✘ $1"; FAIL=$((FAIL + 1)); }
check() { [[ "$2" == "$3" ]] && ok "$1" || ko "$1 (expected '$2', got '$3')"; }

# request METHOD URL [curl args...] -> prints HTTP status, body saved in $BODY
request() { curl -s -o "$BODY" -w '%{http_code}' -X "$1" "$2" "${@:3}"; }
field()   { jq -r "$1 // empty" "$BODY"; }

wait_for_service() { # name url
  for _ in $(seq 1 60); do
    # Any HTTP answer (even 401/404) means the service is up
    [[ "$(curl -s -o /dev/null -w '%{http_code}' "$2")" != "000" ]] && return 0
    sleep 2
  done
  echo "$1 is not reachable at $2" >&2; exit 1
}

echo "Waiting for services..."
wait_for_service auth-service    "$AUTH_URL/api/v1/auth/login"
wait_for_service wallet-service  "$WALLET_URL/api/v1/wallet/balance"
wait_for_service payment-service "$PAYMENT_URL/api/v1/transactions/00000000-0000-0000-0000-000000000000"
wait_for_service notification-service "$NOTIFICATION_URL/api/v1/notifications"

echo "1. Register"
ALICE_NAME="alice${RUN_ID}"; BOB_NAME="bob${RUN_ID}"
check "register $ALICE_NAME -> 201" 201 "$(request POST "$AUTH_URL/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ALICE_NAME\",\"email\":\"$ALICE_NAME@example.com\",\"password\":\"password123\"}")"
check "register $BOB_NAME -> 201" 201 "$(request POST "$AUTH_URL/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$BOB_NAME\",\"email\":\"$BOB_NAME@example.com\",\"password\":\"password123\"}")"
BOB_ID="$(field .data.userId)"

echo "2. Login"
check "login -> 200" 200 "$(request POST "$AUTH_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ALICE_NAME\",\"password\":\"password123\"}")"
TOKEN="$(field .data.accessToken)"; REFRESH="$(field .data.refreshToken)"
[[ -n "$TOKEN" ]] && ok "access token received" || { ko "no access token, aborting"; exit 1; }
AUTH=(-H "Authorization: Bearer $TOKEN")

echo "3. Balance (wallet created at registration)"
check "balance -> 200" 200 "$(request GET "$WALLET_URL/api/v1/wallet/balance" "${AUTH[@]}")"
check "starting balance is 0" true "$(jq -r '.data.balance == 0' "$BODY")"

echo "4. Top-up 50,000 (SUCCESS scenario)"
check "topup -> 202 PENDING" "202 PENDING" "$(request POST "$PAYMENT_URL/api/v1/topup" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -H "X-Idempotency-Key: smoke-topup-$RUN_ID" \
  -d '{"amount":50000,"scenario":"SUCCESS","description":"smoke test"}') $(field .data.status)"
TOPUP_ID="$(field .data.transactionId)"

STATUS=""
for _ in $(seq 1 20); do   # gateway webhook arrives ~1.5 s later
  request GET "$PAYMENT_URL/api/v1/transactions/$TOPUP_ID" "${AUTH[@]}" >/dev/null
  STATUS="$(field .data.status)"; [[ "$STATUS" == "PENDING" ]] || break; sleep 1
done
check "top-up settled by webhook" SUCCESS "$STATUS"
request GET "$WALLET_URL/api/v1/wallet/balance" "${AUTH[@]}" >/dev/null
check "balance is 50000" true "$(jq -r '.data.balance == 50000' "$BODY")"

echo "5. Idempotency"
check "same key replays current state -> 200 SUCCESS" "200 SUCCESS" "$(request POST "$PAYMENT_URL/api/v1/topup" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -H "X-Idempotency-Key: smoke-topup-$RUN_ID" \
  -d '{"amount":50000,"scenario":"SUCCESS"}') $(field .data.status)"
check "same key, different amount -> 422" 422 "$(request POST "$PAYMENT_URL/api/v1/topup" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -H "X-Idempotency-Key: smoke-topup-$RUN_ID" \
  -d '{"amount":20000,"scenario":"SUCCESS"}')"

echo "6. Transfer 10,000 to $BOB_NAME"
check "transfer -> 200 SUCCESS" "200 SUCCESS" "$(request POST "$PAYMENT_URL/api/v1/transfer" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -H "X-Idempotency-Key: smoke-transfer-$RUN_ID" \
  -d "{\"toUserId\":\"$BOB_ID\",\"amount\":10000,\"description\":\"smoke test\"}") $(field .data.status)"
check "insufficient balance -> 422 FAILED" "422 FAILED" "$(request POST "$PAYMENT_URL/api/v1/transfer" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -H "X-Idempotency-Key: smoke-transfer-big-$RUN_ID" \
  -d "{\"toUserId\":\"$BOB_ID\",\"amount\":9000000}") $(field .data.status)"

echo "7. Mutation history"
check "mutations -> 200" 200 "$(request GET "$WALLET_URL/api/v1/wallet/mutations?page=0&size=10" "${AUTH[@]}")"
check "2 ledger entries (CREDIT top-up, DEBIT transfer)" "DEBIT,CREDIT" "$(jq -r '[.data.content[].type] | join(",")' "$BODY")"

echo "8. Notifications (delivered via payment outbox, ~2 s)"
TYPES=""
for _ in $(seq 1 15); do
  request GET "$NOTIFICATION_URL/api/v1/notifications?page=0&size=10" "${AUTH[@]}" >/dev/null
  TYPES="$(jq -r '[.data.content[].type] | join(",")' "$BODY")"; [[ "$TYPES" == "TRANSFER_SENT,TOPUP_SUCCESS" ]] && break; sleep 1
done
check "inbox: transfer + top-up, newest first" "TRANSFER_SENT,TOPUP_SUCCESS" "$TYPES"
check "top-up body shows nominal" "Saldo Rp50.000 sudah masuk ke GPay kamu." "$(jq -r '.data.content[1].body' "$BODY")"
request GET "$NOTIFICATION_URL/api/v1/notifications/unread-count" "${AUTH[@]}" >/dev/null
check "unread badge is 2" 2 "$(field .data.unread)"
check "read-all -> 200, 2 updated" "200 2" "$(request PATCH "$NOTIFICATION_URL/api/v1/notifications/read-all" "${AUTH[@]}") $(field .data.updated)"
request GET "$NOTIFICATION_URL/api/v1/notifications/unread-count" "${AUTH[@]}" >/dev/null
check "unread badge is 0" 0 "$(field .data.unread)"
request POST "$AUTH_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$BOB_NAME\",\"password\":\"password123\"}" >/dev/null
BOB_TOKEN="$(field .data.accessToken)"
request GET "$NOTIFICATION_URL/api/v1/notifications?page=0&size=10" -H "Authorization: Bearer $BOB_TOKEN" >/dev/null
check "recipient notified with nominal" "TRANSFER_RECEIVED Kamu menerima transfer Rp10.000." \
  "$(jq -r '.data.content[0] | "\(.type) \(.body)"' "$BODY")"

echo "9. Refresh and logout"
check "refresh -> 200" 200 "$(request POST "$AUTH_URL/api/v1/auth/refresh" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}")"
NEW_REFRESH="$(field .data.refreshToken)"
check "logout -> 200" 200 "$(request POST "$AUTH_URL/api/v1/auth/logout" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$NEW_REFRESH\"}")"
check "refresh after logout -> 401" 401 "$(request POST "$AUTH_URL/api/v1/auth/refresh" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$NEW_REFRESH\"}")"

echo
echo "Result: $PASS passed, $FAIL failed"
[[ $FAIL -eq 0 ]]
