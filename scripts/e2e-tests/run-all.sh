#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "========================================"
echo "Priority Analyzer Bridge Result-Traffic Suite"
echo "========================================"
echo "Project dir: ${PROJECT_DIR}"
echo ""

cd "${PROJECT_DIR}"

source "${SCRIPT_DIR}/isolation.sh"
source "${SCRIPT_DIR}/test-support.sh"

for command in docker curl jq; do
    command -v "${command}" >/dev/null || {
        echo "Missing required command: ${command}" >&2
        exit 1
    }
done

BRIDGE_REPOSITORY="$(dirname "$(git rev-parse --git-common-dir)")"
export ANALYZER_MOCK_CONTEXT="${ANALYZER_MOCK_DIR:-$(dirname "${BRIDGE_REPOSITORY}")/analyzer-mock-server}"
if [ ! -f "${ANALYZER_MOCK_CONTEXT}/Dockerfile" ]; then
    echo "Analyzer mock checkout not found at ${ANALYZER_MOCK_CONTEXT}" >&2
    echo "Set ANALYZER_MOCK_DIR to the analyzer-mock-server checkout." >&2
    exit 1
fi

cleanup() {
    docker compose -f docker-compose.test.yml down --volumes --remove-orphans >/dev/null 2>&1 || true
}

finish() {
    local status=$?
    if [ "${status}" -ne 0 ]; then
        echo "" >&2
        echo "Analyzer result-traffic diagnostics:" >&2
        docker compose -f docker-compose.test.yml logs --tail=160 \
            openelis-analyzer-bridge analyzer-mock wiremock >&2 || true
    fi
    cleanup
    exit "${status}"
}
trap finish EXIT

cleanup

# Start services
echo "Starting Docker Compose test environment..."
docker compose -f docker-compose.test.yml up -d --build

echo "Waiting for services to be healthy..."
TIMEOUT=120
ELAPSED=0
while [ $ELAPSED -lt $TIMEOUT ]; do
    if curl --silent --fail "http://localhost:${E2E_BRIDGE_PORT}/actuator/health" >/dev/null 2>&1 \
        && curl --silent --fail "${ANALYZER_MOCK_URL}/health" >/dev/null 2>&1 \
        && curl --silent --fail "${WIREMOCK_URL}/__admin/health" >/dev/null 2>&1; then
        echo "Bridge, analyzer-mock, and OpenELIS capture are ready."
        break
    fi
    sleep 5
    ELAPSED=$((ELAPSED + 5))
    echo "  Waiting... (${ELAPSED}s / ${TIMEOUT}s)"
done

if [ $ELAPSED -ge $TIMEOUT ]; then
    echo "ERROR: Bridge did not become healthy within ${TIMEOUT}s"
    docker compose -f docker-compose.test.yml logs --tail=80
    exit 1
fi

echo "--- Checking the shared listeners exist before any analyzer is configured ---"
for port in "${E2E_ASTM_LIS1A_PORT}" "${E2E_MLLP_PORT}"; do
    if ! (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null; then
        echo "FAIL: nothing accepts a TCP session on host port ${port} with no connection configured" >&2
        exit 1
    fi
done
# The ports this compose file publishes for the bridge are the ports it binds at boot.
listening="$(docker compose -f docker-compose.test.yml exec -T openelis-analyzer-bridge sh -c \
    'cat /proc/net/tcp /proc/net/tcp6 2>/dev/null' \
    | awk 'NR>1 && $4=="0A" {split($2, a, ":"); print a[2]}' | sort -u \
    | while read -r hex; do printf '%d\n' "0x${hex}"; done)"
for published in $(docker compose -f docker-compose.test.yml config --format json \
    | jq -r '.services["openelis-analyzer-bridge"].ports[].target'); do
    if ! grep -qx "${published}" <<<"${listening}"; then
        echo "FAIL: docker-compose.test.yml publishes container port ${published}, but the bridge does not listen on it at boot" >&2
        echo "Listening: $(tr '\n' ' ' <<<"${listening}")" >&2
        exit 1
    fi
done
echo "Listening at boot on every published port: $(tr '\n' ' ' <<<"${listening}")"
echo ""

GENEXPERT_CONNECTION_ID="$(create_connection \
    "genexpert-astm" \
    "oe-e2e-genexpert" \
    "GeneXpert acceptance connection" \
    '{"transport":"TCP/IP","connectionRole":"SERVER"}' 5)"
export GENEXPERT_CONNECTION_ID
# Revision 5 defaults to results-only; this test does not enable a separate outbound destination.
# Before activation the boot listener already holds 12001: the bridge's side must check as ready.
# With no analyzer address saved, the advisory analyzer check is skipped and does not fail the result.
probe="$(probe_connection "${GENEXPERT_CONNECTION_ID}")"
if ! jq --exit-status '
    .status == "SUCCEEDED"
    and (.checks | length == 2)
    and any(.checks[]; .key == "listener" and .status == "PASSED" and .messageKey == "listener.ready")
    and any(.checks[]; .key == "analyzer" and .status == "SKIPPED" and .messageKey == "analyzer.address.missing")' \
    <<<"${probe}" >/dev/null; then
    echo "FAIL: check-connection before activation on the shared port: ${probe}" >&2
    exit 1
fi
echo "Check-connection before activation: listener ready on the shared port, analyzer skipped without an address"
activate_connection "${GENEXPERT_CONNECTION_ID}"

FLUOROCYCLER_CONNECTION_ID="$(create_connection \
    "fluorocycler-xt" \
    "oe-e2e-fluorocycler" \
    "FluoroCycler acceptance connection" \
    '{"directory":"/mnt/analyzer-import"}')"
export FLUOROCYCLER_CONNECTION_ID
activate_connection "${FLUOROCYCLER_CONNECTION_ID}"

HL7_PROFILE_ID="$(publish_hl7_fixture_profile)"
HL7_CONNECTION_ID="$(create_connection \
    "${HL7_PROFILE_ID}" \
    "oe-e2e-hl7" \
    "HL7 acceptance connection" \
    '{"transport":"TCP/IP","connectionRole":"SERVER"}')"
activate_connection "${HL7_CONNECTION_ID}"

echo "--- Running ASTM TCP test ---"
bash "${SCRIPT_DIR}/test-astm-tcp.sh"
echo ""

echo "--- Running HL7 MLLP test ---"
BRIDGE_CONNECTION_ID="${HL7_CONNECTION_ID}" bash "${SCRIPT_DIR}/test-mllp.sh"
echo ""

echo "--- Running delivery outbox DNS-outage test ---"
bash "${SCRIPT_DIR}/test-outbox-dns-outage.sh"
echo ""

echo "--- Running delivery outbox lost-response test ---"
bash "${SCRIPT_DIR}/test-outbox-lost-response.sh"
echo ""

echo "--- Running FILE test ---"
bash "${SCRIPT_DIR}/test-file-csv.sh"
echo ""

echo "--- Running shared-listener attribution test ---"
bash "${SCRIPT_DIR}/test-shared-listener.sh"
echo ""

# Last: recreates the bridge with a different retry budget.
echo "--- Running delivery outbox dead-message-queue test ---"
bash "${SCRIPT_DIR}/test-outbox-dmq-retry.sh"
echo ""

echo "========================================"
echo "PRIORITY RESULT-TRAFFIC TESTS PASSED (7/7)"
echo "========================================"
