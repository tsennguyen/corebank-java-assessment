#!/usr/bin/env bash
# End-to-end API test that calls every operation published in /v3/api-docs:
# CRUD happy paths plus the error cases a reviewer usually tries (401, 403, 404,
# 409, 422, validation 400). Prints one PASS/FAIL line per call and an
# operation-coverage summary computed from the live OpenAPI document.
#
# Usage:
#   scripts/api-test.sh                                  # http://localhost:8080
#   BASE_URL=https://demo.example.ts.net:10000 scripts/api-test.sh
#   scripts/api-test.sh -v                               # also print response bodies
#
# Requires: bash 3.2+, curl, jq. Safe to re-run: every run creates its own data.
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-Admin@123}"
CUSTOMER_USER="${CUSTOMER_USER:-cust.an}"
CUSTOMER_PASS="${CUSTOMER_PASS:-Customer@123}"
OUT_DIR="${OUT_DIR:-./api-test-output}"
VERBOSE=0
[ "${1:-}" = "-v" ] && VERBOSE=1

for bin in curl jq; do
  command -v "$bin" >/dev/null 2>&1 || { echo "Missing dependency: $bin" >&2; exit 2; }
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT_DIR"
: > "$TMP/covered"

if [ -t 1 ]; then G=$'\e[32m'; R=$'\e[31m'; Y=$'\e[33m'; B=$'\e[1m'; D=$'\e[2m'; N=$'\e[0m'
else G=""; R=""; Y=""; B=""; D=""; N=""; fi

PASS=0
FAIL=0
FAILED_LINES=""
STATUS=""
BODY=""

# req METHOD PATH [BODY] [TOKEN] [HEADER...]
req() {
  local method="$1" path="$2" body="${3:-}" token="${4:-}"
  shift 2; [ $# -gt 0 ] && shift; [ $# -gt 0 ] && shift
  local args=(-s --max-time "${TIMEOUT:-30}" -o "$TMP/body" -D "$TMP/headers" -w '%{http_code}' -X "$method" "$BASE_URL$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "${DEMO_KEY:-}" ] && args+=(-H "X-Demo-Key: $DEMO_KEY")
  [ -n "$body" ] && args+=(-H "Content-Type: application/json" --data "$body")
  local h
  for h in "$@"; do args+=(-H "$h"); done
  STATUS="$(curl "${args[@]}" 2>/dev/null)"
  [ -z "$STATUS" ] && STATUS="000"
  BODY="$(cat "$TMP/body" 2>/dev/null || true)"
}

# check "OPERATION TEMPLATE" "label" "expected status regex" METHOD PATH [BODY] [TOKEN] [HEADER...]
check() {
  local op="$1" label="$2" expected="$3"
  shift 3
  req "$@"
  echo "$op" >> "$TMP/covered"
  local detail=""
  if printf '%s' "$BODY" | jq -e 'type=="object" and has("code")' >/dev/null 2>&1; then
    detail="$(printf '%s' "$BODY" | jq -r '"\(.code): \(.detail // .title // "")"' | cut -c1-90)"
  fi
  if [[ "$STATUS" =~ ^($expected)$ ]]; then
    PASS=$((PASS + 1))
    printf "  ${G}PASS${N} %s %-7s %-52s %s ${D}%s${N}\n" "$STATUS" "$1" "$2" "$label" "$detail"
  else
    FAIL=$((FAIL + 1))
    printf "  ${R}FAIL${N} %s %-7s %-52s %s ${R}(expected %s)${N} %s\n" "$STATUS" "$1" "$2" "$label" "$expected" "$detail"
    FAILED_LINES="${FAILED_LINES}  - $label: got $STATUS, expected $expected ($1 $2) $detail"$'\n'
  fi
  if [ "$VERBOSE" = 1 ] && [ -n "$BODY" ]; then
    printf '%s' "$BODY" | jq -C . 2>/dev/null | head -25 | sed 's/^/        /'
  fi
}

# assert "label" CONDITION_EXIT_CODE  — business assertion on data already fetched
assert() {
  local label="$1" ok="$2" info="${3:-}"
  if [ "$ok" = 0 ]; then
    PASS=$((PASS + 1)); printf "  ${G}PASS${N} ${B}assert${N} %s ${D}%s${N}\n" "$label" "$info"
  else
    FAIL=$((FAIL + 1)); printf "  ${R}FAIL${N} ${B}assert${N} %s ${R}%s${N}\n" "$label" "$info"
    FAILED_LINES="${FAILED_LINES}  - assert: $label $info"$'\n'
  fi
}

j() { printf '%s' "$BODY" | jq -r "$1"; }
header() { grep -i "^$1:" "$TMP/headers" | head -1 | cut -d' ' -f2- | tr -d '\r'; }
section() { printf "\n${B}%s${N}\n" "$1"; }
# Decimal comparison without bc: jq handles the numbers.
num_eq() { jq -n --argjson a "$1" --argjson b "$2" '$a == $b' | grep -q true; }

TODAY="$(date +%Y-%m-%d)"
if date -v-30d +%Y-%m-%d >/dev/null 2>&1; then
  FROM30="$(date -v-30d +%Y-%m-%d)"; FROM365="$(date -v-365d +%Y-%m-%d)"; TOMORROW="$(date -v+1d +%Y-%m-%d)"
else
  FROM30="$(date -d '-30 days' +%Y-%m-%d)"; FROM365="$(date -d '-365 days' +%Y-%m-%d)"; TOMORROW="$(date -d '+1 day' +%Y-%m-%d)"
fi
RUN_ID="$(date +%s)"
RUN9="${RUN_ID: -9}"
idem() { echo "api-test-$RUN_ID-$1"; }

printf "${B}Core Banking API test${N}  target=%s  run=%s\n" "$BASE_URL" "$RUN_ID"

# ---------------------------------------------------------------------------
section "0. Health & OpenAPI"
check "GET /actuator/health" "Health check (public)" "200" GET /actuator/health
check "GET /v3/api-docs" "OpenAPI document" "200" GET /v3/api-docs
printf '%s' "$BODY" | jq -r '.paths | to_entries[] | .key as $p | .value | keys[] | "\(ascii_upcase) \($p)"' \
  | sort -u > "$TMP/all_ops" 2>/dev/null || : > "$TMP/all_ops"

# ---------------------------------------------------------------------------
section "1. Auth: JWT login, refresh rotation, reuse detection, logout"
OP_LOGIN="POST /api/v1/auth/login"
# Unknown user (not the admin) so repeated runs never trip the login rate limiter for admin.
check "$OP_LOGIN" "Unknown user / wrong password -> 401" "401" POST /api/v1/auth/login "{\"username\":\"nobody-$RUN_ID\",\"password\":\"wrong\"}"
check "$OP_LOGIN" "Missing fields -> 400" "400" POST /api/v1/auth/login '{}'
check "GET /api/v1/customers" "No token -> 401" "401" GET /api/v1/customers
check "GET /api/v1/customers" "Forged token -> 401" "401" GET /api/v1/customers "" "eyJhbGciOiJIUzI1NiJ9.e30.invalid"
check "$OP_LOGIN" "Admin login" "200" POST /api/v1/auth/login "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}"
REFRESH1="$(j '.refreshToken // empty')"
check "POST /api/v1/auth/refresh" "Refresh token rotation" "200" POST /api/v1/auth/refresh "{\"refreshToken\":\"$REFRESH1\"}"
REFRESH2="$(j '.refreshToken // empty')"
assert "Rotation issues a new refresh token" "$([ -n "$REFRESH2" ] && [ "$REFRESH2" != "$REFRESH1" ]; echo $?)"
check "POST /api/v1/auth/refresh" "Reuse old refresh token -> 401" "401" POST /api/v1/auth/refresh "{\"refreshToken\":\"$REFRESH1\"}"
check "$OP_LOGIN" "Admin login (fresh session)" "200" POST /api/v1/auth/login "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}"
REFRESH3="$(j '.refreshToken // empty')"
check "POST /api/v1/auth/logout" "Logout revokes refresh token" "204" POST /api/v1/auth/logout "{\"refreshToken\":\"$REFRESH3\"}"
check "POST /api/v1/auth/refresh" "Refresh after logout -> 401" "401" POST /api/v1/auth/refresh "{\"refreshToken\":\"$REFRESH3\"}"
req POST /api/v1/auth/login "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}"
ADMIN="$(j '.accessToken // empty')"
if [ -z "$ADMIN" ]; then
  echo "${R}Cannot log in as $ADMIN_USER (status $STATUS). Set ADMIN_PASS and retry.${N}" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
section "2. Customer types (limit policy per segment)"
check "GET /api/v1/customer-types" "List customer types" "200" GET /api/v1/customer-types "" "$ADMIN"
check "GET /api/v1/customer-types/{code}" "Get INDIVIDUAL" "200" GET /api/v1/customer-types/INDIVIDUAL "" "$ADMIN"
CT_NAME="$(j .name)"; CT_TX="$(j .maxTransactionLimit)"; CT_DAY="$(j .maxDailyLimit)"; CT_VER="$(j .version)"
check "PUT /api/v1/customer-types/{code}" "Update INDIVIDUAL (same limits)" "200" PUT /api/v1/customer-types/INDIVIDUAL \
  "{\"name\":\"$CT_NAME\",\"maxTransactionLimit\":$CT_TX,\"maxDailyLimit\":$CT_DAY,\"version\":$CT_VER}" "$ADMIN"
check "PUT /api/v1/customer-types/{code}" "Daily limit < per-tx limit -> 400" "400|422" PUT /api/v1/customer-types/INDIVIDUAL \
  "{\"maxTransactionLimit\":1000.00,\"maxDailyLimit\":10.00}" "$ADMIN"
check "GET /api/v1/customer-types/{code}" "Unknown type -> 404" "404" GET /api/v1/customer-types/NOPE "" "$ADMIN"

# ---------------------------------------------------------------------------
section "3. Customers CRUD (soft delete, optimistic lock, masking)"
NID="000$RUN9"
CUST_BODY="{\"fullName\":\"Khach Hang Test $RUN_ID\",\"nationalId\":\"$NID\",\"email\":\"test$RUN_ID@example.com\",\"phone\":\"09${RUN_ID: -8}\",\"address\":\"1 Duong Mau\",\"location\":\"Ha Noi\",\"dateOfBirth\":\"1990-01-15\",\"customerTypeCode\":\"INDIVIDUAL\"}"
check "POST /api/v1/customers" "Create customer" "201" POST /api/v1/customers "$CUST_BODY" "$ADMIN"
CID="$(j .id)"; CVER="$(j .version)"
assert "National ID is masked in response" "$(j .nationalId | grep -vq "$NID"; echo $?)" "$(j .nationalId)"
check "POST /api/v1/customers" "Duplicate national ID -> 409" "409" POST /api/v1/customers "$CUST_BODY" "$ADMIN"
check "POST /api/v1/customers" "Invalid national ID -> 400" "400" POST /api/v1/customers \
  '{"fullName":"X","nationalId":"123","location":"Ha Noi"}' "$ADMIN"
check "GET /api/v1/customers/{id}" "Get customer" "200" GET "/api/v1/customers/$CID" "" "$ADMIN"
check "GET /api/v1/customers" "Search by name + location" "200" GET "/api/v1/customers?name=Test%20$RUN_ID&location=Ha%20Noi&size=5" "" "$ADMIN"
assert "Search finds the new customer" "$([ "$(j .totalElements)" -ge 1 ] 2>/dev/null; echo $?)" "totalElements=$(j .totalElements)"
check "PUT /api/v1/customers/{id}" "Update customer (version $CVER)" "200" PUT "/api/v1/customers/$CID" \
  "{\"fullName\":\"Khach Hang Test $RUN_ID Updated\",\"location\":\"Ha Noi\",\"email\":\"upd$RUN_ID@example.com\",\"version\":$CVER}" "$ADMIN"
check "PUT /api/v1/customers/{id}" "Stale version -> 409 (optimistic lock)" "409" PUT "/api/v1/customers/$CID" \
  "{\"fullName\":\"Lost Update\",\"location\":\"Ha Noi\",\"version\":$CVER}" "$ADMIN"
check "GET /api/v1/customers/{id}" "Unknown customer -> 404" "404" GET /api/v1/customers/999999999 "" "$ADMIN"

# ---------------------------------------------------------------------------
section "4. Accounts (12-digit Luhn number, limits, optimistic lock)"
check "POST /api/v1/accounts" "Open account A1" "201" POST /api/v1/accounts \
  "{\"customerId\":$CID,\"transactionLimit\":50000000.00,\"dailyLimit\":100000000.00}" "$ADMIN"
A1="$(j .id)"; A1NUM="$(j .accountNumber)"; A1VER="$(j .version)"
assert "Account number is 12 digits" "$(printf '%s' "$A1NUM" | grep -Eq '^[0-9]{12}$'; echo $?)" "$A1NUM"
check "POST /api/v1/accounts" "Open account A2" "201" POST /api/v1/accounts \
  "{\"customerId\":$CID,\"transactionLimit\":50000000.00,\"dailyLimit\":100000000.00}" "$ADMIN"
A2="$(j .id)"
check "POST /api/v1/accounts" "Open account A3 (to be closed)" "201" POST /api/v1/accounts \
  "{\"customerId\":$CID,\"transactionLimit\":1000000.00,\"dailyLimit\":2000000.00}" "$ADMIN"
A3="$(j .id)"
check "POST /api/v1/accounts" "Unknown customer -> 404" "404" POST /api/v1/accounts \
  '{"customerId":999999999,"transactionLimit":1000.00,"dailyLimit":2000.00}' "$ADMIN"
check "POST /api/v1/accounts" "Zero limit -> 400" "400" POST /api/v1/accounts \
  "{\"customerId\":$CID,\"transactionLimit\":0,\"dailyLimit\":0}" "$ADMIN"
check "GET /api/v1/accounts/{id}" "Get account A1" "200" GET "/api/v1/accounts/$A1" "" "$ADMIN"
check "GET /api/v1/accounts" "List accounts of customer" "200" GET "/api/v1/accounts?customerId=$CID&status=ACTIVE" "" "$ADMIN"
assert "Customer has 3 accounts" "$([ "$(j .totalElements)" = 3 ]; echo $?)" "totalElements=$(j .totalElements)"
check "PATCH /api/v1/accounts/{id}" "Update limits (version $A1VER)" "200" PATCH "/api/v1/accounts/$A1" \
  "{\"transactionLimit\":50000000.00,\"dailyLimit\":120000000.00,\"version\":$A1VER}" "$ADMIN"
check "PATCH /api/v1/accounts/{id}" "Stale version -> 409" "409" PATCH "/api/v1/accounts/$A1" \
  "{\"transactionLimit\":1.00,\"dailyLimit\":1.00,\"version\":$A1VER}" "$ADMIN"

# ---------------------------------------------------------------------------
section "5. Transactions: deposit, withdraw, transfer, payment, idempotency, reversal"
check "GET /api/v1/fees/preview" "Fee preview TRANSFER 15,000,000" "200" GET "/api/v1/fees/preview?type=TRANSFER&amount=15000000" "" "$ADMIN"
check "GET /api/v1/fees/preview" "Negative amount -> 400" "400" GET "/api/v1/fees/preview?type=TRANSFER&amount=-1" "" "$ADMIN"
DEP_BODY="{\"accountId\":$A1,\"amount\":20000000.00,\"description\":\"API test deposit\",\"location\":\"Ha Noi\"}"
check "POST /api/v1/transactions/deposits" "Deposit 20,000,000 into A1" "201" POST /api/v1/transactions/deposits "$DEP_BODY" "$ADMIN" "Idempotency-Key: $(idem dep)"
DEP_ID="$(j .id)"
check "POST /api/v1/transactions/deposits" "Replay same Idempotency-Key" "200|201" POST /api/v1/transactions/deposits "$DEP_BODY" "$ADMIN" "Idempotency-Key: $(idem dep)"
assert "Replay returns the original transaction" "$([ "$(j .id)" = "$DEP_ID" ]; echo $?)" "id=$(j .id) Idempotent-Replayed=$(header Idempotent-Replayed)"
req GET "/api/v1/accounts/$A1" "" "$ADMIN"
assert "Balance credited exactly once" "$(num_eq "$(j .balance)" 20000000; echo $?)" "balance=$(j .balance)"
check "POST /api/v1/transactions/deposits" "Same key, different payload -> 409/422" "409|422" POST /api/v1/transactions/deposits \
  "{\"accountId\":$A1,\"amount\":1.00}" "$ADMIN" "Idempotency-Key: $(idem dep)"
check "POST /api/v1/transactions/deposits" "Amount 0 -> 400" "400" POST /api/v1/transactions/deposits "{\"accountId\":$A1,\"amount\":0}" "$ADMIN" "Idempotency-Key: $(idem zero)"
check "POST /api/v1/transactions/deposits" "Unknown account -> 404" "404" POST /api/v1/transactions/deposits '{"accountId":999999999,"amount":1000.00}' "$ADMIN" "Idempotency-Key: $(idem nf)"
check "POST /api/v1/transactions/withdrawals" "Missing Idempotency-Key -> 400" "400" POST /api/v1/transactions/withdrawals \
  "{\"accountId\":$A1,\"amount\":1000.00}" "$ADMIN"
check "POST /api/v1/transactions/withdrawals" "Withdraw 2,000,000 from A1" "201" POST /api/v1/transactions/withdrawals \
  "{\"accountId\":$A1,\"amount\":2000000.00,\"location\":\"Ha Noi\"}" "$ADMIN" "Idempotency-Key: $(idem wd)"
check "POST /api/v1/transactions/withdrawals" "Insufficient balance -> 422" "422" POST /api/v1/transactions/withdrawals \
  "{\"accountId\":$A1,\"amount\":40000000.00}" "$ADMIN" "Idempotency-Key: $(idem nsf)"
check "POST /api/v1/transactions/withdrawals" "Over per-transaction limit -> 422" "422" POST /api/v1/transactions/withdrawals \
  "{\"accountId\":$A1,\"amount\":60000000.00}" "$ADMIN" "Idempotency-Key: $(idem lim)"
check "POST /api/v1/transactions/transfers" "Transfer 5,000,000 A1 -> A2" "201" POST /api/v1/transactions/transfers \
  "{\"sourceAccountId\":$A1,\"targetAccountId\":$A2,\"amount\":5000000.00,\"description\":\"API test transfer\"}" "$ADMIN" "Idempotency-Key: $(idem tr)"
TR_ID="$(j .id)"; TR_FEE="$(j .fee)"
check "POST /api/v1/transactions/transfers" "Transfer to same account -> 400/422" "400|422" POST /api/v1/transactions/transfers \
  "{\"sourceAccountId\":$A1,\"targetAccountId\":$A1,\"amount\":1000.00}" "$ADMIN" "Idempotency-Key: $(idem self)"
check "POST /api/v1/transactions/payments" "Payment 1,000,000 from A1" "201" POST /api/v1/transactions/payments \
  "{\"sourceAccountId\":$A1,\"amount\":1000000.00,\"recipientInfo\":\"Dien luc Mau\",\"description\":\"Hoa don dien\"}" "$ADMIN" "Idempotency-Key: $(idem pay)"
PAY_ID="$(j .id)"
check "GET /api/v1/transactions/{id}" "Get transaction" "200" GET "/api/v1/transactions/$TR_ID" "" "$ADMIN"
check "PATCH /api/v1/transactions/{id}" "Update description (metadata only)" "200" PATCH "/api/v1/transactions/$PAY_ID" \
  '{"description":"Hoa don dien thang 10","location":"Ha Noi"}' "$ADMIN"
req GET "/api/v1/accounts/$A2" "" "$ADMIN"; A2_BEFORE="$(j .balance)"
check "DELETE /api/v1/transactions/{id}" "Reverse the transfer (REVERSAL entry)" "200" DELETE "/api/v1/transactions/$TR_ID" "" "$ADMIN"
assert "Reversal links to original" "$([ "$(j .reversalOfId)" = "$TR_ID" ]; echo $?)" "type=$(j .type) reversalOfId=$(j .reversalOfId)"
req GET "/api/v1/accounts/$A2" "" "$ADMIN"
assert "A2 balance returned to 0 after reversal" "$(num_eq "$(j .balance)" 0; echo $?)" "before=$A2_BEFORE after=$(j .balance)"
check "DELETE /api/v1/transactions/{id}" "Reverse twice -> 409" "409|422" DELETE "/api/v1/transactions/$TR_ID" "" "$ADMIN"
check "GET /api/v1/transactions/{id}" "Unknown transaction -> 404" "404" GET /api/v1/transactions/999999999 "" "$ADMIN"
check "GET /api/v1/transactions" "Multi-filter search" "200" GET \
  "/api/v1/transactions?accountId=$A1&type=DEPOSIT&status=COMPLETED&minAmount=1000&maxAmount=50000000&fromDate=$TODAY&toDate=$TODAY&sort=createdAt,desc" "" "$ADMIN"
assert "Search returns the deposit" "$([ "$(j '.totalElements')" -ge 1 ] 2>/dev/null; echo $?)" "totalElements=$(j .totalElements)"
check "GET /api/v1/transactions" "Invalid amount filter -> 400" "400" GET "/api/v1/transactions?minAmount=abc" "" "$ADMIN"
check "GET /api/v1/accounts/{id}/transactions" "Account history (offset page)" "200" GET "/api/v1/accounts/$A1/transactions?page=0&size=2" "" "$ADMIN"
check "GET /api/v1/accounts/{id}/transactions/cursor" "Account history (keyset cursor, page 1)" "200" GET "/api/v1/accounts/$A1/transactions/cursor?size=2" "" "$ADMIN"
NEXT="$(j '.nextCursor // empty')"
if [ -n "$NEXT" ]; then
  check "GET /api/v1/accounts/{id}/transactions/cursor" "Keyset cursor, page 2" "200" GET "/api/v1/accounts/$A1/transactions/cursor?size=2&cursor=$NEXT" "" "$ADMIN"
fi
check "GET /api/v1/accounts/{id}/transactions/cursor" "Tampered cursor -> 400" "400" GET "/api/v1/accounts/$A1/transactions/cursor?cursor=not-a-cursor" "" "$ADMIN"

# ---------------------------------------------------------------------------
section "6. Account status lifecycle (ACTIVE <-> LOCKED -> CLOSED)"
check "POST /api/v1/accounts/{id}/status" "Lock A2" "200" POST "/api/v1/accounts/$A2/status" '{"targetStatus":"LOCKED","reason":"API test lock"}' "$ADMIN"
check "POST /api/v1/transactions/deposits" "Deposit into LOCKED account -> 409/422" "409|422" POST /api/v1/transactions/deposits "{\"accountId\":$A2,\"amount\":1000.00}" "$ADMIN" "Idempotency-Key: $(idem locked)"
check "POST /api/v1/accounts/{id}/status" "Unlock A2" "200" POST "/api/v1/accounts/$A2/status" '{"targetStatus":"ACTIVE","reason":"API test unlock"}' "$ADMIN"
check "POST /api/v1/accounts/{id}/status" "Missing reason -> 400" "400" POST "/api/v1/accounts/$A2/status" '{"targetStatus":"LOCKED"}' "$ADMIN"
check "GET /api/v1/accounts/{id}/status-history" "Status history of A2" "200" GET "/api/v1/accounts/$A2/status-history" "" "$ADMIN"
assert "History has open + lock + unlock" "$([ "$(j 'length')" -ge 3 ] 2>/dev/null; echo $?)" "entries=$(j 'length')"
check "DELETE /api/v1/accounts/{id}" "Close A1 with balance > 0 -> 409/422" "409|422" DELETE "/api/v1/accounts/$A1" "" "$ADMIN"
check "DELETE /api/v1/accounts/{id}" "Close empty account A3" "204" DELETE "/api/v1/accounts/$A3" "" "$ADMIN"
req GET "/api/v1/accounts/$A3" "" "$ADMIN"
assert "A3 is CLOSED (kept for audit, not hard-deleted)" "$([ "$(j .status)" = CLOSED ]; echo $?)" "status=$(j .status) http=$STATUS"

# ---------------------------------------------------------------------------
section "7. Recurring transactions"
check "POST /api/v1/recurring-transactions" "Create monthly transfer A1 -> A2" "201" POST /api/v1/recurring-transactions \
  "{\"type\":\"TRANSFER\",\"sourceAccountId\":$A1,\"targetAccountId\":$A2,\"amount\":100000.00,\"frequency\":\"MONTHLY\",\"startDate\":\"$TOMORROW\",\"description\":\"Tiet kiem hang thang\"}" "$ADMIN"
RID="$(j .id)"
check "POST /api/v1/recurring-transactions" "TRANSFER without target -> 400/422" "400|422" POST /api/v1/recurring-transactions \
  "{\"type\":\"TRANSFER\",\"sourceAccountId\":$A1,\"amount\":100000.00,\"frequency\":\"MONTHLY\",\"startDate\":\"$TOMORROW\"}" "$ADMIN"
check "GET /api/v1/recurring-transactions/{id}" "Get recurring" "200" GET "/api/v1/recurring-transactions/$RID" "" "$ADMIN"
check "GET /api/v1/recurring-transactions" "List recurring of A1" "200" GET "/api/v1/recurring-transactions?accountId=$A1&status=ACTIVE" "" "$ADMIN"
check "POST /api/v1/recurring-transactions/{id}/pause" "Pause" "200" POST "/api/v1/recurring-transactions/$RID/pause" "" "$ADMIN"
check "POST /api/v1/recurring-transactions/{id}/pause" "Pause twice -> 409" "409" POST "/api/v1/recurring-transactions/$RID/pause" "" "$ADMIN"
check "POST /api/v1/recurring-transactions/{id}/resume" "Resume" "200" POST "/api/v1/recurring-transactions/$RID/resume" "" "$ADMIN"
check "DELETE /api/v1/recurring-transactions/{id}" "Cancel recurring" "204" DELETE "/api/v1/recurring-transactions/$RID" "" "$ADMIN"

# ---------------------------------------------------------------------------
section "8. Customer role: self-service and ownership (IDOR guard)"
check "$OP_LOGIN" "Login $CUSTOMER_USER" "200" POST /api/v1/auth/login "{\"username\":\"$CUSTOMER_USER\",\"password\":\"$CUSTOMER_PASS\"}"
CUST="$(j '.accessToken // empty')"
check "GET /api/v1/me" "Own profile" "200" GET /api/v1/me "" "$CUST"
check "GET /api/v1/me/accounts" "Own accounts" "200" GET "/api/v1/me/accounts?status=ACTIVE" "" "$CUST"
OWN_ACC="$(j 'if type=="array" then .[0].id else .content[0].id end // empty')"
if [ -n "$OWN_ACC" ]; then
  check "GET /api/v1/accounts/{id}" "Own account" "200" GET "/api/v1/accounts/$OWN_ACC" "" "$CUST"
fi
check "GET /api/v1/accounts/{id}" "Someone else's account -> 403" "403" GET "/api/v1/accounts/$A1" "" "$CUST"
check "GET /api/v1/accounts/{id}/transactions" "Someone else's history -> 403" "403" GET "/api/v1/accounts/$A1/transactions" "" "$CUST"
check "GET /api/v1/transactions/{id}" "Someone else's transaction -> 403/404" "403|404" GET "/api/v1/transactions/$DEP_ID" "" "$CUST"
check "GET /api/v1/customers" "Admin-only list -> 403" "403" GET /api/v1/customers "" "$CUST"
check "POST /api/v1/customers" "Admin-only create -> 403" "403" POST /api/v1/customers "$CUST_BODY" "$CUST"
check "GET /api/v1/admin/audit-logs" "Audit log -> 403" "403" GET /api/v1/admin/audit-logs "" "$CUST"
check "GET /api/v1/me" "Admin has no customer profile -> 403/404" "403|404" GET /api/v1/me "" "$ADMIN"

# ---------------------------------------------------------------------------
section "9. Fraud detection: real-time rules, SQL window scan, alert workflow"
check "POST /api/v1/transactions/deposits" "Deposit 60,000,000 into A2 (incoming: no alert)" "201" POST /api/v1/transactions/deposits \
  "{\"accountId\":$A2,\"amount\":60000000.00,\"description\":\"Large cash deposit\"}" "$ADMIN" "Idempotency-Key: $(idem big-in)"
check "POST /api/v1/transactions/transfers" "Transfer 50,000,000 A2 -> A1 (outgoing >= 50M)" "201" POST /api/v1/transactions/transfers \
  "{\"sourceAccountId\":$A2,\"targetAccountId\":$A1,\"amount\":50000000.00,\"description\":\"Large outgoing transfer\"}" "$ADMIN" "Idempotency-Key: $(idem big-out)"
BIG_ID="$(j .id)"
for i in 1 2 3 4 5 6; do
  req POST /api/v1/transactions/payments "{\"sourceAccountId\":$A1,\"amount\":10000.00,\"recipientInfo\":\"Burst $i\"}" "$ADMIN" "Idempotency-Key: $(idem burst-$i)"
done
assert "6 payments from A1 within 60 seconds accepted" "$([ "$STATUS" = 201 ]; echo $?)" "last status=$STATUS"
sleep 3  # real-time detection runs asynchronously after commit
check "GET /api/v1/alerts" "List alerts for A2" "200" GET "/api/v1/alerts?accountId=$A2" "" "$ADMIN"
ALERT_ID="$(printf '%s' "$BODY" | jq -r --argjson t "${BIG_ID:-0}" '.content[] | select(.transactionId == $t and .ruleCode == "AMOUNT_THRESHOLD") | .id' 2>/dev/null | head -1)"
assert "Outgoing 50M raised AMOUNT_THRESHOLD (HIGH)" "$([ -n "$ALERT_ID" ]; echo $?)" "alertId=${ALERT_ID:-none}"
assert "Incoming deposit raised no alert" "$([ "$(j '[.content[] | select(.transactionId != ('"${BIG_ID:-0}"'))] | length')" = 0 ]; echo $?)"
check "GET /api/v1/alerts" "List alerts for A1" "200" GET "/api/v1/alerts?accountId=$A1&ruleCode=VELOCITY" "" "$ADMIN"
assert "Burst of 6 payments raised VELOCITY alert" "$([ "$(j '.totalElements')" -ge 1 ] 2>/dev/null; echo $?)" "velocity alerts=$(j .totalElements)"
check "GET /api/v1/alerts" "Filter alerts by severity + rule" "200" GET "/api/v1/alerts?severity=HIGH&ruleCode=AMOUNT_THRESHOLD&fromDate=$FROM365&toDate=$TODAY" "" "$ADMIN"
if [ -n "$ALERT_ID" ]; then
  check "GET /api/v1/alerts/{id}" "Get alert" "200" GET "/api/v1/alerts/$ALERT_ID" "" "$ADMIN"
  check "POST /api/v1/alerts/{id}/status" "NEW -> INVESTIGATING" "200" POST "/api/v1/alerts/$ALERT_ID/status" '{"targetStatus":"INVESTIGATING","note":"Goi xac minh khach hang"}' "$ADMIN"
  check "POST /api/v1/alerts/{id}/status" "INVESTIGATING -> RESOLVED" "200" POST "/api/v1/alerts/$ALERT_ID/status" '{"targetStatus":"RESOLVED","note":"Khach hang xac nhan"}' "$ADMIN"
  check "POST /api/v1/alerts/{id}/status" "RESOLVED -> INVESTIGATING -> 409" "409|422" POST "/api/v1/alerts/$ALERT_ID/status" '{"targetStatus":"INVESTIGATING"}' "$ADMIN"
fi
check "GET /api/v1/alerts/{id}" "Unknown alert -> 404" "404" GET /api/v1/alerts/999999999 "" "$ADMIN"
check "POST /api/v1/fraud/scans" "Historical scan (SQL window functions)" "200" POST /api/v1/fraud/scans "{\"from\":\"$FROM365\",\"to\":\"$TODAY\"}" "$ADMIN"
check "POST /api/v1/alerts/scan" "Historical scan (alias path), idempotent" "200" POST /api/v1/alerts/scan "{\"from\":\"$FROM365\",\"to\":\"$TODAY\"}" "$ADMIN"
assert "Re-scan creates no duplicate alerts" "$([ "$(j '.alertsCreated')" = 0 ]; echo $?)" "alertsCreated=$(j .alertsCreated)"
check "POST /api/v1/fraud/scans" "Unknown field -> 400" "400" POST /api/v1/fraud/scans "{\"from\":\"$FROM30\",\"to\":\"$TODAY\",\"hack\":1}" "$ADMIN"
check "POST /api/v1/fraud/scans" "Customer cannot scan -> 403" "403" POST /api/v1/fraud/scans "{\"from\":\"$FROM30\",\"to\":\"$TODAY\"}" "$CUST"

# ---------------------------------------------------------------------------
section "10. Statistics, periodic reports, trends, Excel/PDF export"
check "GET /api/v1/statistics/balance-tiers" "Balance tiers (LOW/MEDIUM/HIGH)" "200" GET /api/v1/statistics/balance-tiers "" "$ADMIN"
check "GET /api/v1/statistics/customers-by-location" "Customers by location" "200" GET /api/v1/statistics/customers-by-location "" "$ADMIN"
for p in WEEK QUARTER YEAR; do
  check "GET /api/v1/reports/transactions" "Transaction report by $p" "200" GET "/api/v1/reports/transactions?period=$p&from=$FROM365&to=$TODAY" "" "$ADMIN"
done
check "GET /api/v1/reports/transactions" "from > to -> 400" "400" GET "/api/v1/reports/transactions?period=WEEK&from=$TODAY&to=$FROM30" "" "$ADMIN"
check "GET /api/v1/reports/trends" "Daily trend (system)" "200" GET "/api/v1/reports/trends?granularity=DAY&from=$FROM30&to=$TODAY" "" "$ADMIN"
check "GET /api/v1/reports/trends" "Weekly trend of A1" "200" GET "/api/v1/reports/trends?granularity=WEEK&from=$FROM30&to=$TODAY&accountId=$A1" "" "$ADMIN"
check "GET /api/v1/reports/export" "Export Excel" "200" GET "/api/v1/reports/export?format=XLSX&period=WEEK&from=$FROM365&to=$TODAY" "" "$ADMIN"
cp "$TMP/body" "$OUT_DIR/report-$RUN_ID.xlsx"
assert "Excel file is a ZIP container (xlsx)" "$(head -c 2 "$OUT_DIR/report-$RUN_ID.xlsx" | grep -q PK; echo $?)" "$OUT_DIR/report-$RUN_ID.xlsx ($(wc -c < "$OUT_DIR/report-$RUN_ID.xlsx" | tr -d ' ') bytes)"
check "GET /api/v1/reports/export" "Export PDF" "200" GET "/api/v1/reports/export?format=PDF&period=QUARTER&from=$FROM365&to=$TODAY" "" "$ADMIN"
cp "$TMP/body" "$OUT_DIR/report-$RUN_ID.pdf"
assert "PDF file starts with %PDF" "$(head -c 4 "$OUT_DIR/report-$RUN_ID.pdf" | grep -q '%PDF'; echo $?)" "$OUT_DIR/report-$RUN_ID.pdf ($(wc -c < "$OUT_DIR/report-$RUN_ID.pdf" | tr -d ' ') bytes)"
check "GET /api/v1/reports/export" "Unsupported format -> 400" "400" GET "/api/v1/reports/export?format=CSV&period=WEEK&from=$FROM30&to=$TODAY" "" "$ADMIN"
check "GET /api/v1/reports/export" "Customer cannot export -> 403" "403" GET "/api/v1/reports/export?format=PDF&period=WEEK&from=$FROM30&to=$TODAY" "" "$CUST"

# ---------------------------------------------------------------------------
section "11. Audit log (append-only)"
check "GET /api/v1/admin/audit-logs" "Latest audit entries" "200" GET "/api/v1/admin/audit-logs?size=5&sort=createdAt,desc" "" "$ADMIN"
assert "Audit log has entries from this run" "$([ "$(j '.totalElements')" -ge 1 ] 2>/dev/null; echo $?)" "totalElements=$(j .totalElements)"
check "GET /api/v1/admin/audit-logs" "Filter by actor + date" "200" GET "/api/v1/admin/audit-logs?actor=$ADMIN_USER&fromDate=$TODAY&toDate=$TODAY" "" "$ADMIN"

# ---------------------------------------------------------------------------
section "12. Customer delete (soft delete)"
check "POST /api/v1/customers" "Create throwaway customer" "201" POST /api/v1/customers \
  "{\"fullName\":\"Khach Xoa $RUN_ID\",\"nationalId\":\"000$(( (RUN9 + 7) % 1000000000 ))\",\"location\":\"Da Nang\"}" "$ADMIN"
DEL_ID="$(j .id)"
check "DELETE /api/v1/customers/{id}" "Soft delete customer" "204" DELETE "/api/v1/customers/$DEL_ID" "" "$ADMIN"
check "GET /api/v1/customers/{id}" "Deleted customer is hidden -> 404" "404" GET "/api/v1/customers/$DEL_ID" "" "$ADMIN"
check "DELETE /api/v1/customers/{id}" "Delete again -> 404" "404" DELETE "/api/v1/customers/$DEL_ID" "" "$ADMIN"

# ---------------------------------------------------------------------------
section "Summary"
sort -u "$TMP/covered" > "$TMP/covered_u"
TOTAL_OPS="$(grep -c '/api/' "$TMP/all_ops" 2>/dev/null || echo 0)"
COVERED_OPS="$(grep '/api/' "$TMP/all_ops" | comm -12 - "$TMP/covered_u" | wc -l | tr -d ' ')"
printf "  Checks : ${G}%d passed${N}, %s%d failed${N}\n" "$PASS" "$([ "$FAIL" = 0 ] && echo "$G" || echo "$R")" "$FAIL"
printf "  OpenAPI operations covered: ${B}%s/%s${N}\n" "$COVERED_OPS" "$TOTAL_OPS"
MISSING="$(grep '/api/' "$TMP/all_ops" | comm -23 - "$TMP/covered_u")"
[ -n "$MISSING" ] && printf "  ${Y}Not covered:${N}\n%s\n" "$(echo "$MISSING" | sed 's/^/    /')"
printf "  Exported files: %s\n" "$OUT_DIR"
if [ "$FAIL" -gt 0 ]; then
  printf "\n${R}Failures:${N}\n%s" "$FAILED_LINES"
  exit 1
fi
