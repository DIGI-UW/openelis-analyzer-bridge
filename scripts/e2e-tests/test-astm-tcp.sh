#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/test-support.sh"

connection_id="${GENEXPERT_CONNECTION_ID:?GENEXPERT_CONNECTION_ID is required}"

echo "Sending GeneXpert ASTM traffic through analyzer-mock..."
curl --silent --show-error --fail-with-body \
    --request POST \
    --header 'Content-Type: application/json' \
    --data '{"destination":"tcp://openelis-analyzer-bridge:12001","count":1}' \
    "${ANALYZER_MOCK_URL}/simulate/astm/genexpert_astm" \
    | jq --exit-status '.pushed == 1' >/dev/null

assert_normalized_capture "${connection_id}" "genexpert-astm" "MTB-RIF" "TCP"
echo "GeneXpert ASTM result reached the normalized OpenELIS contract."
