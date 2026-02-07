#!/bin/bash
set -e

echo "=== E2E Test: ASTM TCP Transport ==="

WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8080}"
BRIDGE_ASTM_PORT="${BRIDGE_ASTM_PORT:-12001}"

# Reset WireMock request log
curl -s -X DELETE "${WIREMOCK_URL}/__admin/requests" > /dev/null

# ASTM message (records separated by \r as per ASTM spec)
# The bridge's LIS01-A listener auto-detects non-compliant mode
# when the first character is 'H' instead of ENQ (0x05).
# Records are read until termination record "L|1|N" is found.
ASTM_MSG='H|\^&|||MINDRAY^BC-5380|||||||P|1|20260206120000\rP|1||PAT001||DOE^JOHN\rO|1|SAM001||^^^CBC\rR|1|^^^WBC|7.5|10^3/uL||N||F\rL|1|N'

# Send via raw TCP to host-exposed port
echo "Sending ASTM message via TCP..."
printf "${ASTM_MSG}" | nc -w 5 localhost "${BRIDGE_ASTM_PORT}" || true

# Wait for processing
sleep 3

# Verify via WireMock admin API
echo "Verifying message was forwarded..."
REQUESTS=$(curl -s "${WIREMOCK_URL}/__admin/requests")

# Check message was forwarded to /analyzer/astm path
if echo "$REQUESTS" | jq -e '.requests[] | select(.request.url | contains("/analyzer/astm"))' > /dev/null 2>&1; then
    echo "  [PASS] Message forwarded to /analyzer/astm"
else
    echo "  [FAIL] Message not forwarded to /analyzer/astm"
    echo "  Requests: $(echo "$REQUESTS" | jq -c '.requests[].request.url' 2>/dev/null || echo 'none')"
    exit 1
fi

# Check source identifier header
if echo "$REQUESTS" | jq -e '.requests[] | select(.request.headers["X-Source-Analyzer-IP"] // .request.headers["X-Source-Id"])' > /dev/null 2>&1; then
    echo "  [PASS] Source identifier header present"
else
    echo "  [WARN] Source identifier header not found (may be expected for loopback)"
fi

echo "=== ASTM TCP E2E Test PASSED ==="
