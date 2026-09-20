#!/bin/bash
set -euo pipefail

echo "=== E2E Test: Saved HL7 Connection ==="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/test-support.sh"
BRIDGE_MLLP_PORT="${BRIDGE_MLLP_PORT:-2575}"
: "${BRIDGE_CONNECTION_ID:?Activate a saved HL7 server connection and set BRIDGE_CONNECTION_ID first}"
CONNECTION=$(bridge_api "${BRIDGE_API_URL}/connections/${BRIDGE_CONNECTION_ID}")
echo "${CONNECTION}" | jq -e --argjson port "${BRIDGE_MLLP_PORT}" '
  .actualRuntimeState == "ACTIVE" and
  .activeRuntimeRef.configRevision == .configRevision and
  any(.fields[]; .key == "port" and .currentValue == $port)
' > /dev/null
PROFILE_ID=$(echo "${CONNECTION}" | jq -er '.profileRef.profileId')

# Use a unique message so the test need not delete another test's request log.
MESSAGE_ID="saved-hl7-$(date +%s)-$$"
HL7_MSG=$(printf 'MSH|^~\\&|SPOOF|OTHER|OpenELIS|LAB|20260909120000||ORU^R01|%s|P|2.5.1\rPID|1||PAT001\rOBR|1||%s|PANEL\rOBX|1|NM|TEST^Patient||2|unit\r' "${MESSAGE_ID}" "${MESSAGE_ID}")
ACK=$(printf '\x0b%s\x1c\x0d' "${HL7_MSG}" | nc -w 5 localhost "${BRIDGE_MLLP_PORT}")
if [[ "${ACK}" != *"MSA|AA|${MESSAGE_ID}"* ]]; then
    echo "FAIL: the saved listener did not acknowledge successful delivery"
    exit 1
fi

# The ACK follows durable receipt, not delivery: the bridge stores the result and answers, and
# the outbox dispatcher delivers it. Wait on the OpenELIS stub below rather than on the ACK.
assert_normalized_capture "${BRIDGE_CONNECTION_ID}" "${PROFILE_ID}" "TEST" "MLLP"
echo "PASS: saved connection identity delivered through its own HL7 listener"
