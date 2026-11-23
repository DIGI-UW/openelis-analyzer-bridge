#!/bin/bash
set -euo pipefail

port_dir=$(mktemp -d)
export SERIAL_TEST_PORT="$port_dir/vserial0"
export SERIAL_TEST_PORT_PAIR="$port_dir/vserial1"
socat -d -d "pty,raw,echo=0,link=$SERIAL_TEST_PORT" "pty,raw,echo=0,link=$SERIAL_TEST_PORT_PAIR" &
socat_pid=$!
cleanup() {
    kill "$socat_pid" 2>/dev/null || true
    wait "$socat_pid" 2>/dev/null || true
    rm -f "$SERIAL_TEST_PORT" "$SERIAL_TEST_PORT_PAIR"
    rmdir "$port_dir"
}
trap cleanup EXIT

for _ in {1..20}; do
    [[ -e "$SERIAL_TEST_PORT" && -e "$SERIAL_TEST_PORT_PAIR" ]] && break
    sleep 0.5
done
if [[ ! -e "$SERIAL_TEST_PORT" || ! -e "$SERIAL_TEST_PORT_PAIR" ]]; then
    echo "Could not create the virtual serial port pair" >&2
    exit 1
fi

if [[ $# -eq 0 ]]; then
    set -- test -Dtest=SerialIntegrationTest
fi
mvn -B -ntp "$@"
