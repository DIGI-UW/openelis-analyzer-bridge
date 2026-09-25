#!/bin/bash
#
# Several GeneXperts on the shared LIS1-A listener (12001), each result attributed to the right
# saved connection, and every result the bridge cannot attribute held with its payload.
#
# The mock sends from two real addresses (one per test network) and can name each simulated
# instrument the way a GeneXpert names itself, with its System Name in ASTM H.5 (sender_id).
# Connections are pinned to GeneXpert profile revision 5, which offers host and senderId.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/test-support.sh"

: "${GENEXPERT_CONNECTION_ID:?GENEXPERT_CONNECTION_ID is required}"

MOCK_A="${E2E_SUBNET_PREFIX}.150"
MOCK_B="${E2E_SUBNET_B_PREFIX}.150"
BRIDGE_A="tcp://${E2E_SUBNET_PREFIX}.100:12001"
BRIDGE_B="tcp://${E2E_SUBNET_B_PREFIX}.100:12001"
PROFILE="genexpert-astm"
REVISION=5
RUN="$(date +%s)"
created=()

# The mock accepts SiteYearNum accessions only: DEV01 plus 15 digits.
accession() {
    printf 'DEV0126%010d%03d' "${RUN}" "$1"
}

cleanup() {
    for id in "${created[@]:-}"; do
        [ -n "${id}" ] && deactivate_connection "${id}" >/dev/null 2>&1 || true
    done
    activate_connection "${GENEXPERT_CONNECTION_ID}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Creates a revision-5 GeneXpert connection and sets CONNECTION_ID. Not a command substitution:
# the cleanup list has to survive in this shell.
connection() {
    local name="$1" values="$2"
    CONNECTION_ID="$(create_connection "${PROFILE}" "oe-e2e-${name}-${RUN}" "GeneXpert ${name}" "${values}" "${REVISION}")"
    created+=("${CONNECTION_ID}")
}

values() {
    jq --null-input --compact-output --arg host "${1:-}" --arg sender "${2:-}" '
        {transport: "TCP/IP", connectionRole: "SERVER"}
        + (if $host == "" then {} else {host: $host} end)
        + (if $sender == "" then {} else {senderId: $sender} end)'
}

# Send one GeneXpert result from the given mock address, optionally naming the instrument.
send() {
    local destination="$1" source_ip="$2" sender="$3" accession="$4"
    curl --silent --show-error --fail-with-body \
        --request POST \
        --header 'Content-Type: application/json' \
        --data "$(jq --null-input --compact-output \
            --arg destination "${destination}" --arg source_ip "${source_ip}" \
            --arg sender "${sender}" --arg accession "${accession}" '
            {destination: $destination, source_ip: $source_ip, sample_id: $accession, count: 1}
            + (if $sender == "" then {} else {sender_id: $sender} end)')" \
        "${ANALYZER_MOCK_URL}/simulate/astm/genexpert_astm" \
        | jq --exit-status '.pushed == 1' >/dev/null
}

assert_held_payload() {
    local id="$1" accession="$2"
    outbox_payload "${id}" raw | grep -q "${accession}" || {
        echo "Dead letter ${id} does not hold the payload for ${accession}" >&2
        return 1
    }
}

echo "Setting the single hostless GeneXpert connection aside so every result here must be attributed..."
deactivate_connection "${GENEXPERT_CONNECTION_ID}"

echo "1. Two analyzers on 12001, told apart by address..."
connection by-address-a "$(values "${MOCK_A}")"; by_address_a="${CONNECTION_ID}"
connection by-address-b "$(values "${MOCK_B}")"; by_address_b="${CONNECTION_ID}"
activate_connection "${by_address_a}"
activate_connection "${by_address_b}"
send "${BRIDGE_A}" "${MOCK_A}" "" "$(accession 1)"
send "${BRIDGE_B}" "${MOCK_B}" "" "$(accession 2)"
assert_normalized_capture "${by_address_a}" "${PROFILE}" "MTB-RIF" "TCP"
assert_normalized_capture "${by_address_b}" "${PROFILE}" "MTB-RIF" "TCP"
deactivate_connection "${by_address_a}"
deactivate_connection "${by_address_b}"
echo "   each address reached its own connection."

echo "2. Two analyzers on 12001 without addresses, told apart by their System Name..."
connection by-sender-a "$(values "" "GX-LAB-A")"; by_sender_a="${CONNECTION_ID}"
connection by-sender-b "$(values "" "GX-LAB-B")"; by_sender_b="${CONNECTION_ID}"
activate_connection "${by_sender_a}"
activate_connection "${by_sender_b}"
send "${BRIDGE_A}" "${MOCK_A}" "GX-LAB-B" "$(accession 3)"
send "${BRIDGE_B}" "${MOCK_B}" "GX-LAB-A" "$(accession 4)"
assert_normalized_capture "${by_sender_b}" "${PROFILE}" "MTB-RIF" "TCP"
assert_normalized_capture "${by_sender_a}" "${PROFILE}" "MTB-RIF" "TCP"
echo "   each System Name reached its own connection, whichever address it came from."

echo "3. An instrument no connection names is held, not guessed..."
send "${BRIDGE_A}" "${MOCK_A}" "GX-LAB-Z" "$(accession 5)"
unregistered="$(wait_for_dead_letter UNREGISTERED_SOURCE "GX-LAB-Z" "port 12001")"
assert_held_payload "${unregistered}" "$(accession 5)"
deactivate_connection "${by_sender_a}"
deactivate_connection "${by_sender_b}"
echo "   held as UNREGISTERED_SOURCE with its payload."

echo "4. One unnamed and one named connection behind one address keep distinct ownership..."
connection shared-address-a "$(values "${MOCK_A}")"; shared_a="${CONNECTION_ID}"
connection shared-address-b "$(values "${MOCK_A}" "GX-LAB-B")"; shared_b="${CONNECTION_ID}"
activate_connection "${shared_a}"
activate_connection "${shared_b}"
# A blank System Name cannot belong to the connection that requires GX-LAB-B.
# Only the connection without a sender constraint is eligible.
send "${BRIDGE_A}" "${MOCK_A}" " " "$(accession 6)"
assert_normalized_capture "${shared_a}" "${PROFILE}" "MTB-RIF" "TCP"
send "${BRIDGE_A}" "${MOCK_A}" "GX-LAB-B" "$(accession 7)"
assert_normalized_capture "${shared_b}" "${PROFILE}" "MTB-RIF" "TCP"
deactivate_connection "${shared_a}"
deactivate_connection "${shared_b}"
echo "   unnamed traffic reached the unconstrained connection; named traffic reached its own connection."

echo "5. A second connection no message could be told apart from is refused at activation..."
connection twin-a "$(values)"; twin_a="${CONNECTION_ID}"
connection twin-b "$(values)"; twin_b="${CONNECTION_ID}"
activate_connection "${twin_a}"
refusal="$(runtime_command "${twin_b}" ACTIVATE 2>&1 || true)"
if ! grep -q "${twin_a}" <<<"${refusal}"; then
    echo "Activating an indistinguishable second connection was not refused with the first named: ${refusal}" >&2
    exit 1
fi
deactivate_connection "${twin_a}"
echo "   refused, naming the connection it could not be told apart from."

echo "Several analyzers shared 12001, each attributed correctly; nothing unattributable was lost."
