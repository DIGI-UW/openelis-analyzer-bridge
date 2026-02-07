#!/bin/bash
set -e

echo "=== Setting up virtual serial ports ==="
socat -d -d pty,raw,echo=0,link=/tmp/vserial0 pty,raw,echo=0,link=/tmp/vserial1 &
SOCAT_PID=$!

# Poll until ports exist (max 10s)
for i in $(seq 1 20); do
    [ -e /tmp/vserial0 ] && [ -e /tmp/vserial1 ] && break
    sleep 0.5
done

if [ ! -e /tmp/vserial0 ] || [ ! -e /tmp/vserial1 ]; then
    echo "ERROR: Virtual serial ports not created"
    kill $SOCAT_PID 2>/dev/null || true
    exit 1
fi

echo "Virtual serial ports: /tmp/vserial0 <-> /tmp/vserial1"

export SERIAL_TEST_PORT=/tmp/vserial0
export SERIAL_TEST_PORT_PAIR=/tmp/vserial1

echo "=== Running Serial Integration Tests ==="

# Build lib first (required dependency), then run serial tests
cd /app/astm-http-lib && mvn clean install -DskipTests -Dmaven.test.skip=true && cd /app
mvn test -Dtest=SerialIntegrationTest -DfailIfNoTests=false
TEST_EXIT=$?

kill $SOCAT_PID 2>/dev/null || true
exit $TEST_EXIT
