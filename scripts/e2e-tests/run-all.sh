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
    if curl --silent --fail http://localhost:8443/actuator/health >/dev/null 2>&1 \
        && curl --silent --fail http://localhost:18080/health >/dev/null 2>&1 \
        && curl --silent --fail http://localhost:8080/__admin/health >/dev/null 2>&1; then
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

curl --silent --show-error --fail-with-body \
    --request POST \
    --header 'Content-Type: application/json' \
    --data '{"request":{"method":"POST","urlPath":"/api/OpenELIS-Global/analyzer/fhir"},"response":{"status":202,"jsonBody":{"accepted":true}}}' \
    "${WIREMOCK_URL}/__admin/mappings" >/dev/null

GENEXPERT_CONNECTION_ID="$(create_connection \
    "genexpert-astm" \
    "oe-e2e-genexpert" \
    "GeneXpert acceptance connection" \
    '{"transport":"TCP/IP","connectionRole":"SERVER","port":12001}')"
export GENEXPERT_CONNECTION_ID
activate_connection "${GENEXPERT_CONNECTION_ID}"

FLUOROCYCLER_CONNECTION_ID="$(create_connection \
    "fluorocycler-xt" \
    "oe-e2e-fluorocycler" \
    "FluoroCycler acceptance connection" \
    '{"directory":"/mnt/analyzer-import"}')"
export FLUOROCYCLER_CONNECTION_ID
activate_connection "${FLUOROCYCLER_CONNECTION_ID}"

echo "--- Running ASTM TCP test ---"
bash "${SCRIPT_DIR}/test-astm-tcp.sh"
echo ""

echo "--- Running FILE test ---"
bash "${SCRIPT_DIR}/test-file-csv.sh"
echo ""

echo "========================================"
echo "PRIORITY RESULT-TRAFFIC TESTS PASSED (2/2)"
echo "========================================"
