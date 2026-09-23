#!/bin/bash
set -euo pipefail

echo "=== E2E Test: Saved HL7 Connection ==="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/test-support.sh"
# The saved connection declares the container's MLLP port; the test reaches it through the host port
# the stack publishes it on, which differ when the acceptance stack is isolated.
BRIDGE_MLLP_CONNECTION_PORT="${BRIDGE_MLLP_CONNECTION_PORT:-2575}"
BRIDGE_MLLP_PORT="${BRIDGE_MLLP_PORT:-${E2E_MLLP_PORT:-2575}}"
: "${BRIDGE_CONNECTION_ID:?Activate a saved HL7 server connection and set BRIDGE_CONNECTION_ID first}"
CONNECTION=$(bridge_api "${BRIDGE_API_URL}/connections/${BRIDGE_CONNECTION_ID}")
echo "${CONNECTION}" | jq -e --argjson port "${BRIDGE_MLLP_CONNECTION_PORT}" '
  .actualRuntimeState == "ACTIVE" and
  .activeRuntimeRef.configRevision == .configRevision and
  any(.fields[]; .key == "port" and .currentValue == $port)
' > /dev/null
PROFILE_ID=$(echo "${CONNECTION}" | jq -er '.profileRef.profileId')

# Use a unique message so the test need not delete another test's request log.
MESSAGE_ID="saved-hl7-$(date +%s)-$$"
HL7_MSG=$(printf 'MSH|^~\\&|SPOOF|OTHER|OpenELIS|LAB|20260909120000||ORU^R01|%s|P|2.5.1\rPID|1||PAT001\rOBR|1||%s|PANEL\rOBX|1|NM|TEST^Patient||2|unit\r' "${MESSAGE_ID}" "${MESSAGE_ID}")
# Send one MLLP frame and read until the end-of-block byte. Not nc: BSD nc closes the socket as soon
# as its input ends, before the ACK arrives, while other variants wait.
ACK=$(python3 -c '
import socket, sys
port, message = int(sys.argv[1]), sys.argv[2]
with socket.create_connection(("localhost", port), timeout=10) as conn:
    conn.sendall(b"\x0b" + message.encode("utf-8") + b"\x1c\r")
    reply = b""
    while b"\x1c" not in reply:
        chunk = conn.recv(4096)
        if not chunk:
            break
        reply += chunk
print(reply.decode("utf-8", "replace"))
' "${BRIDGE_MLLP_PORT}" "${HL7_MSG}")
if [[ "${ACK}" != *"MSA|AA|${MESSAGE_ID}"* ]]; then
    echo "FAIL: the saved listener did not acknowledge successful delivery"
    exit 1
fi

# The ACK follows durable receipt, not delivery: the bridge stores the result and answers, and
# the outbox dispatcher delivers it. Wait on the OpenELIS stub below rather than on the ACK.
assert_normalized_capture "${BRIDGE_CONNECTION_ID}" "${PROFILE_ID}" "TEST" "MLLP"
echo "PASS: HL7 result on the shared MLLP listener delivered with its saved connection identity"
