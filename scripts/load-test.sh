#!/usr/bin/env bash
# Bulk data + concurrency test through the public REST API (no DB access needed).
#
#   Phase 1  Create N customers in parallel, each with one account and a 10,000,000 VND deposit.
#   Phase 2  Fire T concurrent transfers in both directions between a small "hot" set of accounts
#            (the deadlock-prone case: A->B and B->A at the same time).
#   Phase 3  Verify money is conserved: sum(balances after) == sum(before) - sum(fees charged),
#            no 5xx, no deadlock; print latency percentiles and throughput.
#
# Usage:
#   scripts/load-test.sh                                   # 100 customers, 300 transfers, 25 workers
#   CUSTOMERS=500 TRANSFERS=1000 PARALLEL=50 scripts/load-test.sh
#   BASE_URL=https://demo.example.ts.net:10000 scripts/load-test.sh
#
# Requires: bash, curl, jq, xargs.
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-Admin@123}"
CUSTOMERS="${CUSTOMERS:-100}"
TRANSFERS="${TRANSFERS:-300}"
PARALLEL="${PARALLEL:-25}"
HOT_ACCOUNTS="${HOT_ACCOUNTS:-10}"

for bin in curl jq xargs; do
  command -v "$bin" >/dev/null 2>&1 || { echo "Missing dependency: $bin" >&2; exit 2; }
done
if [ -t 1 ]; then G=$'\e[32m'; R=$'\e[31m'; B=$'\e[1m'; N=$'\e[0m'; else G=""; R=""; B=""; N=""; fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
RUN_ID="$(date +%s)"
export BASE_URL WORK RUN_ID TIMEOUT="${TIMEOUT:-30}" DEMO_KEY="${DEMO_KEY:-}"

now_ms() { python3 -c 'import time; print(int(time.time()*1000))' 2>/dev/null || echo $(( $(date +%s) * 1000 )); }

TOKEN="$(curl -s -X POST "$BASE_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
  ${DEMO_KEY:+-H "X-Demo-Key: $DEMO_KEY"} \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" | jq -r '.accessToken // empty')"
[ -z "$TOKEN" ] && { echo "${R}Admin login failed at $BASE_URL${N}" >&2; exit 1; }
export TOKEN

api() { # api METHOD PATH [BODY] [IDEMPOTENCY_KEY] -> prints "<status> <time_total>\n<body>"
  local args=(-s --max-time "${TIMEOUT:-30}" -w '\n%{http_code} %{time_total}' -X "$1" "$BASE_URL$2" -H "Authorization: Bearer $TOKEN")
  [ -n "$DEMO_KEY" ] && args+=(-H "X-Demo-Key: $DEMO_KEY")
  [ -n "${3:-}" ] && args+=(-H 'Content-Type: application/json' --data "$3")
  [ -n "${4:-}" ] && args+=(-H "Idempotency-Key: $4")
  curl "${args[@]}"
}
export -f api

# One worker of phase 1: customer -> account -> deposit. Writes "accountId status".
onboard() {
  local i="$1" nid out cid aid st
  nid="$(printf '000%09d' $(( (RUN_ID % 100000) * 10000 + i )))"
  out="$(api POST /api/v1/customers "{\"fullName\":\"Load Test $RUN_ID-$i\",\"nationalId\":\"$nid\",\"location\":\"Ha Noi\",\"customerTypeCode\":\"INDIVIDUAL\"}")"
  cid="$(printf '%s' "$out" | sed '$d' | jq -r '.id // empty')"
  [ -z "$cid" ] && { echo "- $(printf '%s' "$out" | tail -1 | cut -d' ' -f1)" >> "$WORK/onboard"; return; }
  out="$(api POST /api/v1/accounts "{\"customerId\":$cid,\"transactionLimit\":50000000.00,\"dailyLimit\":500000000.00}")"
  aid="$(printf '%s' "$out" | sed '$d' | jq -r '.id // empty')"
  [ -z "$aid" ] && { echo "- $(printf '%s' "$out" | tail -1 | cut -d' ' -f1)" >> "$WORK/onboard"; return; }
  out="$(api POST /api/v1/transactions/deposits "{\"accountId\":$aid,\"amount\":10000000.00}" "load-$RUN_ID-dep-$i")"
  st="$(printf '%s' "$out" | tail -1 | cut -d' ' -f1)"
  echo "$aid $st" >> "$WORK/onboard"
}
export -f onboard

# One worker of phase 2. Writes "status fee seconds errorCode".
transfer() {
  local i="$1" src="$2" dst="$3" amount="$4" out last body
  out="$(api POST /api/v1/transactions/transfers "{\"sourceAccountId\":$src,\"targetAccountId\":$dst,\"amount\":$amount,\"description\":\"load $i\"}" "load-$RUN_ID-tr-$i")"
  last="$(printf '%s' "$out" | tail -1)"
  body="$(printf '%s' "$out" | sed '$d')"
  echo "$last $(printf '%s' "$body" | jq -r '"\(.fee // 0) \(.code // "-")"' 2>/dev/null || echo '0 PARSE')" >> "$WORK/transfers"
}
export -f transfer

percentile() { # percentile FILE P -> value from a sorted column
  local n idx; n="$(wc -l < "$1" | tr -d ' ')"; [ "$n" = 0 ] && { echo 0; return; }
  idx=$(( (n * $2 + 99) / 100 )); [ "$idx" -lt 1 ] && idx=1
  sed -n "${idx}p" "$1"
}

sum_balances() {
  local total=0 id
  for id in $(cat "$WORK/hot"); do
    total="$(jq -n --argjson t "$total" --argjson b "$(api GET "/api/v1/accounts/$id" | sed '$d' | jq '.balance')" '$t + $b')"
  done
  echo "$total"
}

printf "${B}Load test${N} target=%s customers=%s transfers=%s parallel=%s\n\n" "$BASE_URL" "$CUSTOMERS" "$TRANSFERS" "$PARALLEL"

# ---------------------------------------------------------------- Phase 1
printf "${B}Phase 1${N} onboarding %s customers (customer + account + deposit)...\n" "$CUSTOMERS"
: > "$WORK/onboard"
t0="$(now_ms)"
seq 1 "$CUSTOMERS" | xargs -P "$PARALLEL" -I{} bash -c 'onboard {}'
t1="$(now_ms)"
OK_ACCOUNTS="$(awk '$1 != "-" && $2 == 201' "$WORK/onboard" | wc -l | tr -d ' ')"
printf "  created %s/%s funded accounts in %s ms (%s API calls, ~%s calls/s)\n" \
  "$OK_ACCOUNTS" "$CUSTOMERS" $((t1 - t0)) $((CUSTOMERS * 3)) $(( CUSTOMERS * 3 * 1000 / ((t1 - t0) > 0 ? (t1 - t0) : 1) ))
awk '$1 == "-" {print "  onboarding failure, status " $2}' "$WORK/onboard" | sort | uniq -c

awk '$1 != "-" && $2 == 201 {print $1}' "$WORK/onboard" | head -"$HOT_ACCOUNTS" > "$WORK/hot"
HOT_N="$(wc -l < "$WORK/hot" | tr -d ' ')"
[ "$HOT_N" -lt 2 ] && { echo "${R}Need at least 2 funded accounts for phase 2${N}"; exit 1; }

# ---------------------------------------------------------------- Phase 2
BEFORE="$(sum_balances)"
printf "\n${B}Phase 2${N} %s concurrent transfers across %s hot accounts (both directions)\n" "$TRANSFERS" "$HOT_N"
printf "  sum of hot balances before: %s\n" "$BEFORE"
: > "$WORK/transfers"
awk -v n="$TRANSFERS" -v seed="$RUN_ID" 'BEGIN { srand(seed) } NR > 0 { ids[NR] = $1 } END {
  for (i = 1; i <= n; i++) {
    s = int(rand() * NR) + 1; d = int(rand() * (NR - 1)) + 1; if (d >= s) d++;
    printf "%d %s %s %d.00\n", i, ids[s], ids[d], (int(rand() * 50) + 1) * 10000
  }
}' "$WORK/hot" > "$WORK/plan"
t0="$(now_ms)"
xargs -P "$PARALLEL" -L 1 bash -c 'transfer "$0" "$1" "$2" "$3"' < "$WORK/plan"
t1="$(now_ms)"
AFTER="$(sum_balances)"

# ---------------------------------------------------------------- Phase 3
printf "\n${B}Phase 3${N} results\n"
printf "  elapsed %s ms, throughput ~%s transfers/s\n" $((t1 - t0)) $(( TRANSFERS * 1000 / ((t1 - t0) > 0 ? (t1 - t0) : 1) ))
printf "  status breakdown:\n"; awk '{print $1, $4}' "$WORK/transfers" | sort | uniq -c | sed 's/^/    /'
awk '{print $2}' "$WORK/transfers" | sort -n > "$WORK/lat"
printf "  latency p50=%ss p95=%ss p99=%ss max=%ss\n" "$(percentile "$WORK/lat" 50)" "$(percentile "$WORK/lat" 95)" "$(percentile "$WORK/lat" 99)" "$(tail -1 "$WORK/lat")"
FEES="$(awk '$1 == 201 {s += $3} END {printf "%.2f", s + 0}' "$WORK/transfers")"
EXPECTED="$(jq -n --argjson b "$BEFORE" --argjson f "$FEES" '$b - $f')"
OK_COUNT="$(awk '$1 == 201' "$WORK/transfers" | wc -l | tr -d ' ')"
ERR5="$(awk '$1 >= 500 || $1 == "000"' "$WORK/transfers" | wc -l | tr -d ' ')"
printf "  completed transfers: %s, fees charged: %s\n" "$OK_COUNT" "$FEES"
printf "  sum after: %s, expected (before - fees): %s\n" "$AFTER" "$EXPECTED"

FAIL=0
if jq -n --argjson a "$AFTER" --argjson e "$EXPECTED" '$a == $e' | grep -q true; then
  echo "  ${G}PASS${N} money conserved to the last dong"
else
  echo "  ${R}FAIL${N} balance mismatch"; FAIL=1
fi
if [ "$ERR5" = 0 ]; then echo "  ${G}PASS${N} no 5xx / deadlock / dropped connection"; else echo "  ${R}FAIL${N} $ERR5 server errors"; FAIL=1; fi
if [ "$OK_COUNT" -gt 0 ]; then echo "  ${G}PASS${N} $OK_COUNT/$TRANSFERS transfers committed (422 = business rule such as insufficient balance)"; else FAIL=1; fi
exit "$FAIL"
