#!/bin/bash
#
# The Madagascar incident, reproduced and then survived.
#
# OpenELIS is unreachable, an analyzer sends a result anyway, the bridge
# is restarted mid-outage, and OpenELIS comes back. The result must arrive, once,
# with its content intact, without the analyzer sending anything again.
#
# Before the delivery outbox this sequence lost the result within three seconds
# and left an 800-character fragment behind.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/test-support.sh"

connection_id="${GENEXPERT_CONNECTION_ID:?GENEXPERT_CONNECTION_ID is required}"

echo "Taking OpenELIS off the network so its name stops resolving..."
openelis_stop

echo "Sending a GeneXpert result into the outage..."
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
echo "  analyzer sent accession ${accession}"

id="$(wait_for_outbox_entry "${accession}" 30)"
echo "  bridge is holding it as ${id}"

wait_for_outbox_state "${id}" "RETRYING" 60

# The failure must be recorded as never having reached OpenELIS. The exact
# exception is deliberately not asserted: the JVM caches name lookups, so the
# first attempt into an outage surfaces as a connect timeout and later ones as an
# unknown host. Both mean the same thing and both must be retried.
entry="$(outbox_api "/${id}")"
jq --exit-status '.lastHttpStatus == null' <<<"${entry}" >/dev/null || {
    echo "Expected a delivery that never reached OpenELIS; got:" >&2
    jq '{lastError, lastHttpStatus}' <<<"${entry}" >&2
    exit 1
}
jq --exit-status '.lastError | test("UnknownHostException|ConnectException|HttpConnectTimeoutException")' \
    <<<"${entry}" >/dev/null || {
    echo "Expected a connection-level failure to be recorded; got:" >&2
    jq '{lastError}' <<<"${entry}" >&2
    exit 1
}
echo "  recorded as undelivered: $(jq --raw-output '.lastError' <<<"${entry}")"

# The whole message, not a fragment. This is the assertion the incident failed:
# all that survived then was an 800-character snippet of the rendered bundle.
raw="$(outbox_payload "${id}" raw)"
grep -q "${accession}" <<<"${raw}" || {
    echo "The stored message does not contain the accession the analyzer sent" >&2
    exit 1
}
grep -q '^H|' <<<"${raw}" || {
    echo "The stored message is not the complete ASTM message (no header record)" >&2
    exit 1
}
fhir="$(outbox_payload "${id}" fhir)"
jq --exit-status --arg id "${id}" '.identifier.value == $id' <<<"${fhir}" >/dev/null || {
    echo "The stored bundle does not carry the delivery identity OpenELIS deduplicates on" >&2
    exit 1
}
echo "  complete message held: ${#raw} bytes received, ${#fhir} bytes rendered"

echo "Restarting the bridge while the result is still undelivered..."
bridge_restart
[ "$(outbox_state "${id}")" != "" ] || {
    echo "The outbox entry did not survive the restart" >&2
    exit 1
}
echo "  entry survived the restart as $(outbox_state "${id}")"

echo "Bringing OpenELIS back..."
# Recreated, so its request journal starts empty: anything counted below arrived
# after recovery, which is what the single-delivery assertion depends on.
openelis_start

wait_for_outbox_state "${id}" "DELIVERED" 120

delivered="$(outbox_api "/${id}")"
jq --exit-status '.oeReceipt != null' <<<"${delivered}" >/dev/null || {
    echo "Delivery was recorded without OpenELIS's receipt reference" >&2
    exit 1
}
count="$(wiremock_deliveries_for_id "${id}")"
[ "${count}" = "1" ] || {
    echo "Expected OpenELIS to be sent this result exactly once after recovery, got ${count}" >&2
    exit 1
}

echo "Result survived a DNS outage and a bridge restart, and arrived once."
