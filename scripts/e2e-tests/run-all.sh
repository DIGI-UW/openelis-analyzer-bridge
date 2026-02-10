#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "========================================"
echo "Universal Analyzer Bridge E2E Test Suite"
echo "========================================"
echo "Project dir: ${PROJECT_DIR}"
echo ""

cd "${PROJECT_DIR}"

# Ensure test data directories exist
mkdir -p test-data/file-input test-data/file-archive test-data/file-error test-data/wiremock/mappings

# Start services
echo "Starting Docker Compose test environment..."
docker compose -f docker-compose.test.yml up -d --build

echo "Waiting for services to be healthy..."
TIMEOUT=60
ELAPSED=0
while [ $ELAPSED -lt $TIMEOUT ]; do
    if docker compose -f docker-compose.test.yml ps --format json 2>/dev/null | \
       jq -e 'select(.Service == "openelis-analyzer-bridge" and .Health == "healthy")' > /dev/null 2>&1; then
        echo "Bridge is healthy!"
        break
    fi
    sleep 5
    ELAPSED=$((ELAPSED + 5))
    echo "  Waiting... (${ELAPSED}s / ${TIMEOUT}s)"
done

if [ $ELAPSED -ge $TIMEOUT ]; then
    echo "ERROR: Bridge did not become healthy within ${TIMEOUT}s"
    docker compose -f docker-compose.test.yml logs openelis-analyzer-bridge | tail -30
    docker compose -f docker-compose.test.yml down
    exit 1
fi

# Check Prometheus endpoint
echo ""
echo "--- Verifying Prometheus metrics endpoint ---"
if curl -s http://localhost:8443/actuator/prometheus | head -5 > /dev/null 2>&1; then
    echo "  [PASS] Prometheus endpoint is accessible"
else
    echo "  [WARN] Prometheus endpoint not accessible (may not be critical)"
fi

# Check health endpoint
echo ""
echo "--- Verifying health endpoint ---"
HEALTH=$(curl -s http://localhost:8443/actuator/health)
echo "  Health: $(echo "$HEALTH" | jq -r '.status' 2>/dev/null || echo 'unknown')"

# Run individual tests
echo ""
FAILED=0

echo "--- Running ASTM TCP test ---"
bash "${SCRIPT_DIR}/test-astm-tcp.sh" || FAILED=$((FAILED + 1))
echo ""

echo "--- Running MLLP HL7 test ---"
bash "${SCRIPT_DIR}/test-mllp.sh" || FAILED=$((FAILED + 1))
echo ""

echo "--- Running File CSV test ---"
bash "${SCRIPT_DIR}/test-file-csv.sh" || FAILED=$((FAILED + 1))
echo ""

echo "--- Running HTTP /input test ---"
bash "${SCRIPT_DIR}/test-http-input.sh" || FAILED=$((FAILED + 1))
echo ""

# Cleanup main test environment before serial tests (uses separate compose)
echo "Stopping Docker Compose environment..."
docker compose -f docker-compose.test.yml down

echo ""
echo "--- Running Serial Port test ---"
bash "${SCRIPT_DIR}/test-serial.sh" || FAILED=$((FAILED + 1))
echo ""

echo ""
echo "========================================"
if [ $FAILED -eq 0 ]; then
    echo "ALL E2E TESTS PASSED (5/5)"
    echo "========================================"
    exit 0
else
    echo "FAILED: ${FAILED}/5 tests failed"
    echo "========================================"
    exit 1
fi
