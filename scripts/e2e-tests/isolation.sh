#!/bin/bash
#
# Gives a local acceptance run its own compose project, host ports and test subnet, so it can run
# next to any other stack on the machine without stopping or reusing it.
#
# CI keeps the fixed defaults from docker-compose.test.yml: its runner has nothing else on it.
# Any variable already set in the environment is respected, so a run can still be pinned by hand.

set -euo pipefail

if [ -n "${CI:-}" ]; then
    return 0 2>/dev/null || exit 0
fi

isolation_project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
isolation_digest="$(printf '%s' "${isolation_project_dir}" | shasum -a 256 | cut -c1-8)"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-bridge-e2e-${isolation_digest}}"

# A port the kernel just handed out and released is free at the moment compose binds it.
free_port() {
    python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

for isolation_var in E2E_BRIDGE_PORT E2E_WIREMOCK_PORT E2E_MOCK_PORT E2E_ASTM_LIS1A_PORT E2E_ASTM_E1381_PORT E2E_MLLP_PORT; do
    if [ -z "${!isolation_var:-}" ]; then
        export "${isolation_var}=$(free_port)"
    fi
done

# Docker refuses a network whose subnet overlaps an existing one, so take free /24s.
isolation_used_subnets="$(docker network inspect $(docker network ls --quiet) \
    --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}' 2>/dev/null || true)"
free_subnet_prefix() {
    local skip="${1:-}" octet third candidate
    for octet in $(seq 28 31); do
        for third in $(seq 0 255); do
            candidate="172.${octet}.${third}"
            if [ "${candidate}" != "${skip}" ] \
                && ! grep -q "${candidate}\." <<<"${isolation_used_subnets}" \
                && ! grep -q "172\.${octet}\.0\.0/16" <<<"${isolation_used_subnets}"; then
                printf '%s' "${candidate}"
                return 0
            fi
        done
    done
    return 1
}
if [ -z "${E2E_SUBNET_PREFIX:-}" ]; then
    export E2E_SUBNET_PREFIX="$(free_subnet_prefix)"
fi
if [ -z "${E2E_SUBNET_B_PREFIX:-}" ]; then
    export E2E_SUBNET_B_PREFIX="$(free_subnet_prefix "${E2E_SUBNET_PREFIX}")"
fi

echo "Isolated acceptance stack: project=${COMPOSE_PROJECT_NAME} bridge=:${E2E_BRIDGE_PORT} wiremock=:${E2E_WIREMOCK_PORT} mock=:${E2E_MOCK_PORT} subnets=${E2E_SUBNET_PREFIX}.0/24,${E2E_SUBNET_B_PREFIX}.0/24"
