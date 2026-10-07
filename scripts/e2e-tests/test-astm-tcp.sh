#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/test-support.sh"

connection_id="${GENEXPERT_CONNECTION_ID:?GENEXPERT_CONNECTION_ID is required}"

echo "Replaying Cepheid's HIV-1 viral load message through analyzer-mock..."
# Cepheid's message reports a test completed in November 2022, long before it is sent, so the
# delivered time cannot be mistaken for the time of receipt.
curl --silent --show-error --fail-with-body \
    --request POST \
    --header 'Content-Type: application/json' \
    --data '{"destination":"tcp://openelis-analyzer-bridge:12001","sample_id":"DEV01260000000000101"}' \
    "${ANALYZER_MOCK_URL}/simulate/fixture/genexpert_astm/hivvl/quantified" \
    | jq --exit-status '.pushed == 1' >/dev/null

assert_normalized_capture "${connection_id}" "cepheid-genexpert-astm" "HIVVL" "TCP"
echo "GeneXpert ASTM result reached the normalized OpenELIS contract."

# The bridge runs with TZ=Pacific/Port_Moresby (UTC+10, no daylight saving).
expected_time="2022-11-15T08:40:08+10:00"
body="$(wait_for_normalized_capture "${connection_id}" | jq --raw-output '.request.body')"
if ! jq --exit-status --arg expected "${expected_time}" \
    '[.entry[].resource | select(.resourceType == "Observation") | .effectiveDateTime | select(. != null)]
        | length > 0 and all(. == $expected)' <<<"${body}" >/dev/null; then
    echo "FAIL: expected every timed GeneXpert observation to carry the instrument's completion time ${expected_time}" >&2
    echo "      Times near now mean the receipt time replaced the instrument's (R.13 of Cepheid's message)." >&2
    jq '[.entry[].resource | select(.resourceType == "Observation") | {code: .code.coding[0].code, effectiveDateTime}]' <<<"${body}" >&2
    exit 1
fi
# Cepheid times the test's own records (R.12, R.13); its analyte and control records carry no
# time, and none is invented for them.
echo "GeneXpert result carries the instrument's completion time (${expected_time})."
