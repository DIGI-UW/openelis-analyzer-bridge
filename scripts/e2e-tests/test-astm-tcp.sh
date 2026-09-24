#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/test-support.sh"

connection_id="${GENEXPERT_CONNECTION_ID:?GENEXPERT_CONNECTION_ID is required}"

echo "Sending GeneXpert ASTM traffic through analyzer-mock..."
# A test completed long before it is sent, as on a real instrument, so the delivered time cannot be
# mistaken for the time of receipt.
curl --silent --show-error --fail-with-body \
    --request POST \
    --header 'Content-Type: application/json' \
    --data '{"destination":"tcp://openelis-analyzer-bridge:12001","count":1,"completed_at":"20251021161230"}' \
    "${ANALYZER_MOCK_URL}/simulate/astm/genexpert_astm" \
    | jq --exit-status '.pushed == 1' >/dev/null

assert_normalized_capture "${connection_id}" "genexpert-astm" "MTB-RIF" "TCP"
echo "GeneXpert ASTM result reached the normalized OpenELIS contract."

# The bridge runs with TZ=Pacific/Port_Moresby (UTC+10, no daylight saving).
expected_time="2025-10-21T16:12:30+10:00"
body="$(wait_for_normalized_capture "${connection_id}" | jq --raw-output '.request.body')"
if ! jq --exit-status --arg expected "${expected_time}" \
    '[.entry[].resource | select(.resourceType == "Observation") | .effectiveDateTime]
        | length > 0 and all(. == $expected)' <<<"${body}" >/dev/null; then
    echo "FAIL: expected every GeneXpert observation to carry the instrument's completion time ${expected_time}" >&2
    echo "      Times near now mean the analyzer mock ignored completed_at: check its pin in .github/workflows/test.yml." >&2
    jq '[.entry[].resource | select(.resourceType == "Observation") | {code: .code.coding[0].code, effectiveDateTime}]' <<<"${body}" >&2
    exit 1
fi
echo "GeneXpert result carries the instrument's completion time (${expected_time})."
