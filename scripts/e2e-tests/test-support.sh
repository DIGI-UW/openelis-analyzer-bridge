#!/bin/bash

set -euo pipefail

BRIDGE_API_URL="${BRIDGE_API_URL:-http://localhost:8443/api}"
BRIDGE_USER="${BRIDGE_USER:-bridge}"
BRIDGE_PASSWORD="${BRIDGE_PASSWORD:-changeme}"
WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8080}"
ANALYZER_MOCK_URL="${ANALYZER_MOCK_URL:-http://localhost:18080}"
NORMALIZED_PATH="/api/OpenELIS-Global/analyzer/fhir"

bridge_api() {
    curl --silent --show-error --fail-with-body \
        --user "${BRIDGE_USER}:${BRIDGE_PASSWORD}" \
        "$@"
}

profile_fingerprint() {
    local profile_id="$1"

    bridge_api "${BRIDGE_API_URL}/profiles/${profile_id}?revision=1" \
        | jq --exit-status --raw-output '.profile.catalog.revisionFingerprint'
}

create_connection() {
    local profile_id="$1"
    local client_analyzer_id="$2"
    local display_name="$3"
    local values="$4"
    local fingerprint request response

    fingerprint="$(profile_fingerprint "${profile_id}")"
    request="$(jq --null-input --compact-output \
        --arg request_id "create-${client_analyzer_id}" \
        --arg client_analyzer_id "${client_analyzer_id}" \
        --arg profile_id "${profile_id}" \
        --arg fingerprint "${fingerprint}" \
        --arg display_name "${display_name}" \
        --argjson values "${values}" \
        '{
            schemaVersion: "1.0",
            requestId: $request_id,
            clientAnalyzerId: $client_analyzer_id,
            profileRef: {
                profileId: $profile_id,
                revision: 1,
                fingerprint: $fingerprint
            },
            displayName: $display_name,
            values: $values
        }')"
    response="$(bridge_api \
        --request POST \
        --header 'Content-Type: application/json' \
        --data "${request}" \
        "${BRIDGE_API_URL}/connections")"

    jq --exit-status --raw-output '.connectionId' <<<"${response}"
}

activate_connection() {
    local connection_id="$1"
    local command

    command="$(jq --null-input --compact-output \
        --arg command_id "activate-${connection_id}" \
        --arg connection_id "${connection_id}" \
        '{
            schemaVersion: "1.0",
            commandId: $command_id,
            connectionId: $connection_id,
            action: "ACTIVATE",
            expectedConfigRevision: 1
        }')"

    bridge_api \
        --request POST \
        --header 'Content-Type: application/json' \
        --data "${command}" \
        "${BRIDGE_API_URL}/connections/${connection_id}/runtime" \
        | jq --exit-status '.outcome == "APPLIED" or .outcome == "ALREADY_APPLIED"' >/dev/null
}

wait_for_normalized_capture() {
    local connection_id="$1"
    local capture

    for _ in $(seq 1 45); do
        capture="$(curl --silent --show-error --fail "${WIREMOCK_URL}/__admin/requests")"
        if jq --exit-status --compact-output \
            --arg path "${NORMALIZED_PATH}" \
            --arg connection_id "${connection_id}" \
            '.requests[]
                | select(.request.url == $path)
                | select(.request.body | contains($connection_id))' \
            <<<"${capture}" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done

    echo "No normalized request arrived for Bridge connection ${connection_id}" >&2
    curl --silent "${WIREMOCK_URL}/__admin/requests" | jq '.requests[].request.url' >&2 || true
    return 1
}

assert_normalized_capture() {
    local connection_id="$1"
    local profile_id="$2"
    local raw_code="$3"
    local source_transport="$4"
    local capture body

    capture="$(wait_for_normalized_capture "${connection_id}")"

    jq --exit-status '
        ([.request.headers | keys[] | ascii_downcase]
            | all(.[]; ((startswith("x-source-") or . == "x-analyzer-id") | not)))
        and
        ([.request.headers
            | to_entries[]
            | select(.key | ascii_downcase == "content-type")
            | .value]
            | flatten
            | any(.[]; (ascii_downcase | startswith("application/fhir+json"))))' \
        <<<"${capture}" >/dev/null

    body="$(jq --exit-status --raw-output '.request.body' <<<"${capture}")"
    jq --exit-status \
        --arg connection_id "${connection_id}" \
        --arg profile_id "${profile_id}" \
        --arg raw_code "${raw_code}" \
        --arg source_transport "${source_transport}" \
        '
        .resourceType == "Bundle"
        and any(.entry[].resource;
            .resourceType == "Device"
            and any(.identifier[]?;
                .system == "https://openelis-global.org/fhir/analyzer-connection-id"
                and .value == $connection_id)
            and any(.extension[]?;
                .url == "https://openelis-global.org/fhir/StructureDefinition/analyzer-profile-id"
                and .valueString == $profile_id))
        and any(.entry[].resource;
            .resourceType == "Observation"
            and any(.code.coding[]?;
                .system == "https://openelis-global.org/fhir/CodeSystem/analyzer-raw-code"
                and .code == $raw_code)
            and any(.extension[]?;
                .url == "https://openelis-global.org/fhir/StructureDefinition/analyzer-source-transport"
                and .valueCode == $source_transport)
            and any(.extension[]?;
                .url == "https://openelis-global.org/fhir/StructureDefinition/analyzer-result-classification"
                and .valueCode == "PATIENT")
            and any(.extension[]?;
                .url == "https://openelis-global.org/fhir/StructureDefinition/analyzer-raw-value"))' \
        <<<"${body}" >/dev/null
}
