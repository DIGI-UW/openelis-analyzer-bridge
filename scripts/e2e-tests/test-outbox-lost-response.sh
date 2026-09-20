#!/bin/bash
#
# OpenELIS accepts a result but its answer never arrives.
#
# The bridge cannot tell this apart from a delivery that failed, so it sends the
# result again. That is only safe because every attempt carries the same
# content-derived identity, which OpenELIS deduplicates on: the repeat is
# recognized as the delivery it already recorded rather than staged as a second
# clinical result.
#
# This test proves the bridge half: the retry happens, and it carries the
# identity of the first attempt unchanged.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/test-support.sh"

: "${GENEXPERT_CONNECTION_ID:?GENEXPERT_CONNECTION_ID is required}"

created_mappings=()

cleanup_mappings() {
    local mapping
    for mapping in "${created_mappings[@]:-}"; do
        [ -n "${mapping}" ] && curl --silent --request DELETE "${WIREMOCK_URL}/__admin/mappings/${mapping}" >/dev/null 2>&1 || true
    done
    curl --silent --request POST "${WIREMOCK_URL}/__admin/scenarios/reset" >/dev/null 2>&1 || true
}
trap cleanup_mappings EXIT

stub() {
    curl --silent --show-error --fail-with-body \
        --request POST \
        --header 'Content-Type: application/json' \
        --data "$1" \
        "${WIREMOCK_URL}/__admin/mappings" \
        | jq --raw-output '.id'
}

echo "Arranging for OpenELIS to accept the first delivery but lose its answer..."
created_mappings+=("$(stub '{
  "priority": 1,
  "scenarioName": "lost-response",
  "requiredScenarioState": "Started",
  "newScenarioState": "answering",
  "request": { "method": "POST", "urlPath": "/api/OpenELIS-Global/analyzer/fhir" },
  "response": { "fault": "CONNECTION_RESET_BY_PEER" }
}')")
created_mappings+=("$(stub '{
  "priority": 1,
  "scenarioName": "lost-response",
  "requiredScenarioState": "answering",
  "request": { "method": "POST", "urlPath": "/api/OpenELIS-Global/analyzer/fhir" },
  "response": {
    "status": 200,
    "jsonBody": { "success": true, "receiptId": "wiremock-receipt-replay", "resultsStaged": 1 }
  }
}')")

echo "Sending a GeneXpert result..."
accession="$(
    curl --silent --show-error --fail-with-body \
        --request POST \
        --header 'Content-Type: application/json' \
        --data '{"destination":"tcp://openelis-analyzer-bridge:12001","count":1}' \
        "${ANALYZER_MOCK_URL}/simulate/astm/genexpert_astm" \
        | jq --raw-output '.results[0].sample_id // .sample_id // empty'
)"
[ -n "${accession}" ] || {
    echo "The analyzer mock did not report which sample it sent" >&2
    exit 1
}

id="$(wait_for_outbox_entry "${accession}" 30)"
wait_for_outbox_state "${id}" "DELIVERED" 60

entry="$(outbox_api "/${id}")"
attempts="$(jq --raw-output '.attempts' <<<"${entry}")"
[ "${attempts}" -ge 2 ] || {
    echo "Expected the lost answer to cost an extra attempt, got ${attempts}" >&2
    jq '{state, attempts, lastError}' <<<"${entry}" >&2
    exit 1
}

# Both attempts must have carried the same delivery identity. Without that,
# OpenELIS would have no way to recognize the second as a repeat.
sent="$(wiremock_deliveries_for_id "${id}")"
[ "${sent}" -ge 2 ] || {
    echo "Expected OpenELIS to be sent this identity more than once, got ${sent}" >&2
    exit 1
}

echo "A lost answer cost one extra attempt (${attempts}), both carrying the same delivery identity."
