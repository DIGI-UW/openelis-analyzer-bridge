#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/test-support.sh"

connection_id="${FLUOROCYCLER_CONNECTION_ID:?FLUOROCYCLER_CONNECTION_ID is required}"

echo "Dropping a FluoroCycler export through analyzer-mock..."
curl --silent --show-error --fail-with-body \
    --request POST \
    --header 'Content-Type: application/json' \
    --data '{"target_dir":"/data/analyzer-imports","filename":"fluorocycler-m4-results.xlsx"}' \
    "${ANALYZER_MOCK_URL}/simulate/file/hain_fluorocycler" \
    | jq --exit-status '.written_path != null' >/dev/null

assert_normalized_capture "${connection_id}" "fluorocycler-xt" "VIH-1" "FILE"
echo "FluoroCycler FILE result reached the normalized OpenELIS contract."
