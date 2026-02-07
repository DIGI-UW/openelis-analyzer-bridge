#!/bin/bash
set -e

echo "=== E2E Test: HTTP /input Endpoint ==="

WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8080}"
BRIDGE_HTTP_URL="${BRIDGE_HTTP_URL:-http://localhost:8443}"

# Reset WireMock request log
curl -s -X DELETE "${WIREMOCK_URL}/__admin/requests" > /dev/null

# Sample ASTM message
ASTM_MSG='H|\^&|||MINDRAY^BC-5380|||||||P|1|20260206120000
P|1||PAT001||DOE^JOHN
O|1|SAM001||^^^CBC
R|1|^^^WBC|7.5|10^3/uL||N||F
L|1|N'

# POST to /input endpoint
echo "Sending ASTM message via HTTP /input..."
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "${BRIDGE_HTTP_URL}/input" \
    -H "Content-Type: text/plain" \
    -d "${ASTM_MSG}")

echo "  HTTP response status: ${HTTP_STATUS}"

# Wait for processing
sleep 3

# Verify via WireMock admin API
echo "Verifying message was forwarded..."
REQUESTS=$(curl -s "${WIREMOCK_URL}/__admin/requests")

# Check message was forwarded (should auto-detect ASTM protocol)
if echo "$REQUESTS" | jq -e '.requests[] | select(.request.url | contains("/analyzer/"))' > /dev/null 2>&1; then
    FORWARD_URL=$(echo "$REQUESTS" | jq -r '.requests[-1].request.url')
    echo "  [PASS] Message forwarded to ${FORWARD_URL}"
else
    echo "  [FAIL] Message not forwarded"
    echo "  Requests: $(echo "$REQUESTS" | jq -c '.requests[].request.url' 2>/dev/null || echo 'none')"
    exit 1
fi

echo "=== HTTP Input E2E Test PASSED ==="
