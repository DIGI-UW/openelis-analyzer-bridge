#!/bin/bash
#
# The retry budget runs out, and an operator recovers the result anyway.
#
# A bridge with a deliberately tiny budget is pointed at an OpenELIS that is not
# there. The result must end in the dead-message queue with its complete payload
# and a reason, and one operator retry must deliver it once OpenELIS is back.
#
# Runs last: it recreates the bridge with a different retry budget.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/test-support.sh"

: "${GENEXPERT_CONNECTION_ID:?GENEXPERT_CONNECTION_ID is required}"

restore_bridge() {
    OUTBOX_MAX_ATTEMPTS=150 docker compose -f "${COMPOSE_FILE}" up -d --no-deps --force-recreate \
        openelis-analyzer-bridge >/dev/null 2>&1 || true
}
trap restore_bridge EXIT

echo "Recreating the bridge with a two-attempt budget..."
OUTBOX_MAX_ATTEMPTS=2 docker compose -f "${COMPOSE_FILE}" up -d --no-deps --force-recreate \
    openelis-analyzer-bridge >/dev/null 2>&1
for _ in $(seq 1 60); do
    outbox_api "/stats" >/dev/null 2>&1 && break
    sleep 1
done

echo "Taking OpenELIS away..."
openelis_stop

echo "Sending a GeneXpert result with nowhere to deliver it..."
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
    openelis_start
    exit 1
}

id="$(wait_for_outbox_entry "${accession}" 30)"
wait_for_outbox_state "${id}" "DMQ" 90

entry="$(outbox_api "/${id}")"
jq --exit-status '.failureReason == "RETRY_EXHAUSTED"' <<<"${entry}" >/dev/null || {
    echo "Expected an exhausted retry budget; got:" >&2
    jq '{state, failureReason, attempts, lastError}' <<<"${entry}" >&2
    openelis_start
    exit 1
}

# Exhausting retries must never cost the result. This is the difference between
# the dead-message queue and what the bridge used to do, which was drop it.
raw="$(outbox_payload "${id}" raw)"
grep -q "${accession}" <<<"${raw}" || {
    echo "The dead-lettered entry does not hold the message it received" >&2
    openelis_start
    exit 1
}
fhir="$(outbox_payload "${id}" fhir)"
jq --exit-status --arg id "${id}" '.identifier.value == $id' <<<"${fhir}" >/dev/null || {
    echo "The dead-lettered entry does not hold a deliverable bundle" >&2
    openelis_start
    exit 1
}
echo "  dead-lettered with its complete payload: ${#raw} bytes received, ${#fhir} bytes rendered"

echo "Bringing OpenELIS back and retrying as an operator would..."
openelis_start
outbox_retry "${id}"
wait_for_outbox_state "${id}" "DELIVERED" 60

delivered="$(outbox_api "/${id}")"
jq --exit-status '.retryRequestedBy != null' <<<"${delivered}" >/dev/null || {
    echo "The operator retry was not recorded against the entry" >&2
    exit 1
}
count="$(wiremock_deliveries_for_id "${id}")"
[ "${count}" = "1" ] || {
    echo "Expected exactly one delivery after the operator retry, got ${count}" >&2
    exit 1
}

echo "An exhausted result was recovered by one operator retry, and arrived once."
