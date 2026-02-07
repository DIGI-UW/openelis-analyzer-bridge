#!/bin/bash
set -e

echo "=== E2E Test: MLLP HL7 Transport ==="

WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8080}"
BRIDGE_MLLP_PORT="${BRIDGE_MLLP_PORT:-2575}"

# Reset WireMock request log
curl -s -X DELETE "${WIREMOCK_URL}/__admin/requests" > /dev/null

# HL7 message: newlines converted to CR per HL7 spec
HL7_MSG=$(cat <<'ENDHL7' | tr '\n' '\r'
MSH|^~\&|TestApp|TestFac|OpenELIS|OpenELIS|20260206120000||ORM^O01|MSG123|P|2.5.1
PID|1||PAT001||Doe^John||19800101|M
ORC|NW|ORD123||||||20260206120000
OBR|1|ORD123||CBC^Complete Blood Count|||20260206120000
ENDHL7
)

# MLLP framing: VT (0x0B) + message + FS (0x1C) + CR (0x0D)
echo "Sending HL7 message via MLLP..."
printf "\x0b%s\x1c\x0d" "${HL7_MSG}" | nc -w 5 localhost "${BRIDGE_MLLP_PORT}" || true

# Wait for processing
sleep 3

# Verify via WireMock admin API
echo "Verifying message was forwarded..."
REQUESTS=$(curl -s "${WIREMOCK_URL}/__admin/requests")

# Check message was forwarded to /analyzer/hl7 path
if echo "$REQUESTS" | jq -e '.requests[] | select(.request.url | contains("/analyzer/hl7"))' > /dev/null 2>&1; then
    echo "  [PASS] Message forwarded to /analyzer/hl7"
else
    echo "  [FAIL] Message not forwarded to /analyzer/hl7"
    echo "  Requests: $(echo "$REQUESTS" | jq -c '.requests[].request.url' 2>/dev/null || echo 'none')"
    exit 1
fi

echo "=== MLLP E2E Test PASSED ==="
