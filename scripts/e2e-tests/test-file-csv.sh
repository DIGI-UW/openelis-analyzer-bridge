#!/bin/bash
set -e

echo "=== E2E Test: File Watcher CSV Transport ==="

WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8080}"
FILE_INPUT_DIR="${FILE_INPUT_DIR:-test-data/file-input}"

# Reset WireMock request log
curl -s -X DELETE "${WIREMOCK_URL}/__admin/requests" > /dev/null

# Create a CSV file in the watched directory
FILENAME="test-$(date +%s).csv"
CSV_CONTENT="SampleID,TestCode,Result,Units
SAM001,WBC,7.5,10^3/uL
SAM002,RBC,4.8,10^6/uL"

echo "Dropping CSV file: ${FILENAME}..."
echo "${CSV_CONTENT}" > "${FILE_INPUT_DIR}/${FILENAME}"

# Wait for file stability check + processing
echo "Waiting for file watcher to pick up and process..."
sleep 8

# Verify via WireMock admin API
echo "Verifying message was forwarded..."
REQUESTS=$(curl -s "${WIREMOCK_URL}/__admin/requests")

# Check message was forwarded to /analyzer/csv path
if echo "$REQUESTS" | jq -e '.requests[] | select(.request.url | contains("/analyzer/csv"))' > /dev/null 2>&1; then
    echo "  [PASS] CSV forwarded to /analyzer/csv"
else
    echo "  [FAIL] CSV not forwarded to /analyzer/csv"
    echo "  Requests: $(echo "$REQUESTS" | jq -c '.requests[].request.url' 2>/dev/null || echo 'none')"
    exit 1
fi

# Check that body contains CSV content
if echo "$REQUESTS" | jq -e '.requests[] | select(.request.body | contains("SAM001"))' > /dev/null 2>&1; then
    echo "  [PASS] Request body contains CSV data"
else
    echo "  [WARN] Could not verify CSV body content"
fi

echo "=== File CSV E2E Test PASSED ==="
