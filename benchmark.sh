#!/usr/bin/env bash
# Drives representative traffic at a running gateway and prints the measured result.
#
# The traffic mix is the point: real production traffic is not uniformly unique. A
# support or ops workload asks the same handful of questions in slightly different
# words all day, which is exactly the shape a semantic cache exploits. This script
# models that — a set of base questions, each asked several times with harmless
# rewording — rather than sending random strings that no cache could ever help.
#
# Usage:  ./benchmark.sh [BASE_URL] [ROUNDS]

set -euo pipefail

BASE="${1:-http://localhost:8080}"
ROUNDS="${2:-4}"
TENANT="benchmark-$$"

# A realistic mix: mostly short routine lookups, plus one long open-ended request.
# The long one drops below the confidence threshold and escalates to the larger model,
# so a run exercises both the cheap-serve and the escalate path rather than only one.
LONG_Q="produce a full written analysis of every regulatory consideration involved in \
clearing a multi-line commercial shipment into the United Kingdom, the United Arab \
Emirates and Canada simultaneously, covering documentation requirements, duty and tax \
treatment, prohibited and restricted goods screening, broker responsibilities, \
pre-arrival processing, hold and release semantics, and the operational escalation \
path when any single jurisdiction rejects the declaration while the others accept it"

BASE_QUESTIONS=(
  "what is the customs status of shipment AWB 125-44821903"
  "why is shipment AWB 125-77450012 being held at the border"
  "explain reason code DOC_MISSING to an operations agent"
  "which documents are required to clear a shipment into Canada"
  "how long does duty payment take to reflect on a held shipment"
  "$LONG_Q"
)

# Harmless rewordings — different strings, same question.
VARIANTS=(
  "%s"
  "%s?"
  "hi, %s"
  "can you tell me %s"
  "quick question: %s please"
)

echo "→ warming up ${BASE}"
if ! curl -sf "${BASE}/actuator/health" >/dev/null; then
  echo "gateway not reachable at ${BASE} — start it with: mvn spring-boot:run" >&2
  exit 1
fi

sent=0
for ((round = 0; round < ROUNDS; round++)); do
  for q in "${BASE_QUESTIONS[@]}"; do
    variant="${VARIANTS[$((RANDOM % ${#VARIANTS[@]}))]}"
    # shellcheck disable=SC2059
    prompt="$(printf "$variant" "$q")"
    curl -sf -X POST "${BASE}/v1/chat" \
      -H 'Content-Type: application/json' \
      -H "X-Tenant-Id: ${TENANT}" \
      -d "$(printf '{"prompt":%s,"maxTokens":256}' "$(printf '%s' "$prompt" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')")" \
      >/dev/null || true
    sent=$((sent + 1))
  done
  printf "  round %d/%d — %d requests sent\n" "$((round + 1))" "$ROUNDS" "$sent"
done

echo
echo "→ measured result after ${sent} requests"
curl -sf "${BASE}/v1/stats" | python3 -c '
import json, sys
s = json.load(sys.stdin)
hit_rate = s["cacheHitRate"] * 100
saved_pct = s["costSavedRatio"] * 100
rows = [
    ("requests",            str(s["requests"])),
    ("cache hit rate",      "%.1f%%  (%d hits)" % (hit_rate, s["cacheHits"])),
    ("mean hit similarity", "%.4f" % s["meanHitSimilarity"]),
    ("escalations",         str(s["escalations"])),
    ("actual spend",        "$%.4f" % s["actualCostUsd"]),
    ("if all-premium",      "$%.4f" % s["counterfactualCostUsd"]),
    ("cost saved",          "$%.4f  (%.1f%%)" % (s["costSavedUsd"], saved_pct)),
    ("p50 / p95 / p99",     "%d / %d / %d ms" % (s["p50LatencyMs"], s["p95LatencyMs"], s["p99LatencyMs"])),
    ("failures",            str(s["failures"])),
]
w = max(len(k) for k, _ in rows)
for k, v in rows:
    print("  " + k.ljust(w) + "   " + v)
print()
print("  by provider:", json.dumps(s["requestsByProvider"]))
print("  breakers:   ", ", ".join("%s=%s" % (k, v["state"]) for k, v in s["breakers"].items()))
'
