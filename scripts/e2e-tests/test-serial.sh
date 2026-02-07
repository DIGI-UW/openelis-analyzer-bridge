#!/bin/bash
set -e

echo "=== E2E Test: Serial Port Transport ==="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "${PROJECT_DIR}"

echo "Building serial test runner..."
docker compose -f docker-compose-serial-test.yml build serial-test-runner

echo "Running serial integration tests (socat + jSerialComm)..."
if docker compose -f docker-compose-serial-test.yml run --rm serial-test-runner; then
    echo "  [PASS] Serial tests passed (ASTM, HL7, auto-detect)"
    EXIT_CODE=0
else
    echo "  [FAIL] Serial integration tests failed"
    EXIT_CODE=1
fi

docker compose -f docker-compose-serial-test.yml down --volumes --remove-orphans 2>/dev/null || true

echo "=== Serial E2E Test $([ $EXIT_CODE -eq 0 ] && echo 'PASSED' || echo 'FAILED') ==="
exit $EXIT_CODE
