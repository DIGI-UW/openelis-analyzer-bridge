#!/bin/bash

set -euo pipefail

# Host ports of the test stack (docker-compose.test.yml); run-all.sh picks free ones outside CI.
E2E_BRIDGE_PORT="${E2E_BRIDGE_PORT:-8443}"
E2E_WIREMOCK_PORT="${E2E_WIREMOCK_PORT:-8080}"
E2E_MOCK_PORT="${E2E_MOCK_PORT:-18080}"

BRIDGE_API_URL="${BRIDGE_API_URL:-http://localhost:${E2E_BRIDGE_PORT}/api}"
BRIDGE_USER="${BRIDGE_USER:-bridge}"
BRIDGE_PASSWORD="${BRIDGE_PASSWORD:-changeme}"
WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:${E2E_WIREMOCK_PORT}}"
ANALYZER_MOCK_URL="${ANALYZER_MOCK_URL:-http://localhost:${E2E_MOCK_PORT}}"
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

# Publish a site HL7 profile the way an operator would (draft, edit, publish) and print its id.
# The bridge ships no HL7 profile; this is the ASTM contract fixture recast as HL7, the same fixture
# Hl7SavedConnectionTest uses.
publish_hl7_fixture_profile() {
    local draft draft_id profile_id candidate

    draft="$(bridge_api --request POST --header 'Content-Type: application/json' \
        --data '{"actor":"e2e","displayName":"Saved HL7 fixture"}' \
        "${BRIDGE_API_URL}/profiles/drafts")"
    draft_id="$(jq --exit-status --raw-output '.draftId' <<<"${draft}")"
    profile_id="$(jq --exit-status --raw-output '.profile.profileMeta.id' <<<"${draft}")"
    candidate="$(jq --compact-output --arg id "${profile_id}" '
        .profileMeta.id = $id
        | .profileMeta.displayName = "Saved HL7 fixture"
        | .protocol = {name: "HL7", version: "2.5.1"}
        | del(.configDefaults.extractionOverrides)
        | .controlResultRecognition = {mode: "RULES", rules: {"control-label":
            {ruleType: "FIELD_EQUALS", targetField: "OBX.3.2", operand: "CONTROL"}}}
        | del(.catalog)' contracts/analyzer/v1/fixtures/analyzer-profile-astm.json)"
    bridge_api --request PUT --header 'Content-Type: application/json' \
        --data "$(jq --null-input --compact-output --argjson profile "${candidate}" '{actor: "e2e", profile: $profile}')" \
        "${BRIDGE_API_URL}/profiles/drafts/${draft_id}" >/dev/null
    bridge_api --request POST --header 'Content-Type: application/json' --data '{"actor":"e2e"}' \
        "${BRIDGE_API_URL}/profiles/drafts/${draft_id}/publish" \
        | jq --exit-status --raw-output '.profile.profileMeta.id'
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

# --- Delivery outbox ---------------------------------------------------------
#
# The bridge holds every received result until OpenELIS accepts it. These helpers
# let a scenario watch that happen: find the entry for an accession, wait for it
# to reach a state, read the payload it is holding, and stop or restart the
# services the delivery depends on.

OUTBOX_URL="${OUTBOX_URL:-http://localhost:${E2E_BRIDGE_PORT}/admin/outbox}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.test.yml}"

outbox_api() {
    local path="$1"
    shift
    curl --silent --show-error --fail-with-body \
        --user "${BRIDGE_USER}:${BRIDGE_PASSWORD}" \
        "$@" \
        "${OUTBOX_URL}${path}"
}

# Identifier of the outbox entry for an accession, or empty if the bridge has none.
outbox_find_by_accession() {
    local accession="$1"
    outbox_api "?limit=200&includeDismissed=true" \
        | jq --raw-output --arg accession "${accession}" \
            'first(.rows[] | select(.accession == $accession) | .id) // empty'
}

# Wait for an accession to appear in the outbox at all, and print its id.
wait_for_outbox_entry() {
    local accession="$1"
    local timeout="${2:-30}"
    local id

    for _ in $(seq 1 "${timeout}"); do
        id="$(outbox_find_by_accession "${accession}")"
        if [ -n "${id}" ]; then
            printf '%s' "${id}"
            return 0
        fi
        sleep 1
    done

    echo "No outbox entry appeared for accession ${accession}" >&2
    outbox_api "?limit=20&includeDismissed=true" | jq '.rows[] | {id, state, accession, failureReason}' >&2 || true
    return 1
}

outbox_state() {
    outbox_api "/$1" | jq --raw-output '.state'
}

wait_for_outbox_state() {
    local id="$1"
    local expected="$2"
    local timeout="${3:-60}"
    local state

    for _ in $(seq 1 "${timeout}"); do
        state="$(outbox_state "${id}" 2>/dev/null || true)"
        if [ "${state}" = "${expected}" ]; then
            return 0
        fi
        sleep 1
    done

    echo "Outbox entry ${id} did not reach ${expected} within ${timeout}s (last state: ${state:-unknown})" >&2
    outbox_api "/${id}" | jq '{state, attempts, failureReason, lastError, lastHttpStatus}' >&2 || true
    return 1
}

# The message the bridge is holding: "raw" as received, or "fhir" as it will be sent.
outbox_payload() {
    local id="$1"
    local part="${2:-raw}"
    outbox_api "/${id}/payload?part=${part}"
}

outbox_retry() {
    outbox_api "/$1/retry" --request POST >/dev/null
}

# Take OpenELIS away by name. The container is removed rather than stopped: a
# stopped container keeps its entry in Docker's embedded DNS, so the bridge would
# see a connect timeout. Removing it makes the name stop resolving, which is the
# failure that lost results in production.
openelis_stop() {
    docker compose -f "${COMPOSE_FILE}" rm --stop --force --volumes wiremock >/dev/null 2>&1
}

openelis_start() {
    docker compose -f "${COMPOSE_FILE}" up -d --no-deps wiremock >/dev/null 2>&1
    for _ in $(seq 1 30); do
        if curl --silent --fail "${WIREMOCK_URL}/__admin/health" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    echo "OpenELIS capture did not come back" >&2
    return 1
}

bridge_restart() {
    docker compose -f "${COMPOSE_FILE}" restart openelis-analyzer-bridge >/dev/null 2>&1
    for _ in $(seq 1 60); do
        if curl --silent --fail "http://localhost:${E2E_BRIDGE_PORT}/actuator/health/readiness" >/dev/null 2>&1 \
            || outbox_api "/stats" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    echo "Bridge did not come back after a restart" >&2
    return 1
}

# How many times OpenELIS was sent a bundle carrying this delivery identity.
wiremock_deliveries_for_id() {
    local delivery_id="$1"
    curl --silent --show-error --fail "${WIREMOCK_URL}/__admin/requests" \
        | jq --arg path "${NORMALIZED_PATH}" --arg id "${delivery_id}" \
            '[.requests[] | select(.request.url == $path) | select(.request.body | contains($id))] | length'
}

reset_wiremock_requests() {
    curl --silent --show-error --fail --request DELETE "${WIREMOCK_URL}/__admin/requests" >/dev/null
}
