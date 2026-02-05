# Quickstart: Testing Bi-Directional ASTM Workflow

**Feature**: 001-bi-directional-astm  
**Date**: 2025-12-03

## Prerequisites

- Docker and Docker Compose installed
- [ASTM Mock Server](https://github.com/DIGI-UW/astm-mock-server) cloned
- ASTM-HTTP Bridge built and running

## Quick Test: Source IP Header

### 1. Start the Bridge

```bash
cd /path/to/astm-http-bridge
docker compose -f docker-compose-dev.yml up -d --build
```

### 2. Start Mock Analyzer

```bash
cd /path/to/astm-mock-server
pip install -r requirements.txt

# Push a test message through the bridge
python server.py --push http://localhost:12001 --analyzer-type HEMATOLOGY --verbose
```

### 3. Verify Header

Check bridge logs for the outgoing request:
```bash
docker logs astm-http-bridge 2>&1 | grep -i "X-Source-Analyzer-IP"
```

Expected output:
```
Added X-Source-Analyzer-IP header: 172.20.0.1
```

## Full Test Environment

### Docker Compose Setup

Create `docker-compose.test.yml`:

```yaml
version: '3.8'

services:
  astm-http-bridge:
    build: .
    ports:
      - "12001:12001"  # ASTM LIS1-A
      - "8443:8443"    # HTTP
    environment:
      LOGGING_LEVEL_ORG_ITECH: DEBUG
    volumes:
      - ./configuration.yml:/app/configuration.yml
    networks:
      astm-net:
        ipv4_address: 172.20.0.100

  mock-analyzer-1:
    image: python:3.11-slim
    working_dir: /app
    volumes:
      - ../astm-mock-server:/app
    command: ["python", "server.py", "--port", "5000"]
    networks:
      astm-net:
        ipv4_address: 172.20.0.10
  
  mock-analyzer-2:
    image: python:3.11-slim
    working_dir: /app
    volumes:
      - ../astm-mock-server:/app
    command: ["python", "server.py", "--port", "5000"]
    networks:
      astm-net:
        ipv4_address: 172.20.0.11

  mock-analyzer-3:
    image: python:3.11-slim
    working_dir: /app
    volumes:
      - ../astm-mock-server:/app
    command: ["python", "server.py", "--port", "5000"]
    networks:
      astm-net:
        ipv4_address: 172.20.0.12

  http-capture:
    image: mitmproxy/mitmproxy:latest
    command: ["mitmdump", "--mode", "regular", "--listen-port", "8080"]
    ports:
      - "8080:8080"
    networks:
      astm-net:
        ipv4_address: 172.20.0.200

networks:
  astm-net:
    driver: bridge
    ipam:
      config:
        - subnet: 172.20.0.0/24
```

### Start Test Environment

```bash
docker compose -f docker-compose.test.yml up -d
```

### Run Multi-Analyzer Test

```bash
# Push from analyzer 1
docker exec -it mock-analyzer-1 python server.py --push http://172.20.0.100:12001 --analyzer-type HEMATOLOGY

# Push from analyzer 2
docker exec -it mock-analyzer-2 python server.py --push http://172.20.0.100:12001 --analyzer-type CHEMISTRY

# Push from analyzer 3
docker exec -it mock-analyzer-3 python server.py --push http://172.20.0.100:12001 --analyzer-type IMMUNOLOGY
```

### Verify Different Source IPs

Check HTTP capture:
```bash
docker logs http-capture 2>&1 | grep "X-Source-Analyzer-IP"
```

Expected output:
```
X-Source-Analyzer-IP: 172.20.0.10
X-Source-Analyzer-IP: 172.20.0.11
X-Source-Analyzer-IP: 172.20.0.12
```

## Test Query Flow (HTTP → ASTM)

### 1. Start Mock Server in Receive Mode

```bash
docker exec -it mock-analyzer-1 python server.py --port 5000 --verbose
```

### 2. Send Query Through Bridge

```bash
# Query the mock analyzer through the bridge
curl -X POST "http://localhost:8443/?forwardAddress=172.20.0.10&forwardPort=5000" \
  -H "Content-Type: text/plain" \
  -d "H|\^&|||"
```

### 3. Verify Response

The bridge should return the field list from the mock server:
```
H|\^&|||MockServer^HEMATOLOGY^1.0
R|1|^^^WBC|10^3/μL|NUMERIC
R|2|^^^RBC|10^6/μL|NUMERIC
...
L|1|N
```

## Test Line Contention

Use the mock server's communication test script:

```bash
cd /path/to/astm-mock-server
python test_communication.py --host localhost --port 12001 --mode query
```

Expected output:
```
✅ ENQ/ACK handshake successful
✅ Query message sent
✅ Line contention detected (server sends ENQ)
✅ Role reversal completed
✅ Field list received
```

## Automated Testing with API Mode

### Start Mock Server with API

```bash
python server.py --push http://localhost:12001 --api-port 8080
```

### Trigger Test Pushes via HTTP

```bash
# Single push
curl -X POST "http://localhost:8080/push?analyzer_type=HEMATOLOGY"

# Multiple pushes
curl -X POST "http://localhost:8080/push?analyzer_type=CHEMISTRY&count=5"

# JSON body
curl -X POST http://localhost:8080/push \
  -H "Content-Type: application/json" \
  -d '{"analyzer_type": "IMMUNOLOGY", "count": 10}'
```

## Troubleshooting

### Bridge Not Receiving Messages

1. Check bridge is running:
   ```bash
   docker ps | grep astm-http-bridge
   ```

2. Check port mapping:
   ```bash
   docker port astm-http-bridge
   ```

3. Check bridge logs:
   ```bash
   docker logs astm-http-bridge
   ```

### Header Not Present

1. Enable debug logging:
   ```bash
   docker run -e LOGGING_LEVEL_ORG_ITECH=DEBUG astm-http-bridge
   ```

2. Check for extraction warnings:
   ```bash
   docker logs astm-http-bridge 2>&1 | grep -i "cannot extract"
   ```

### Mock Server Not Connecting

1. Check network connectivity:
   ```bash
   docker exec mock-analyzer-1 ping 172.20.0.100
   ```

2. Check firewall rules:
   ```bash
   docker network inspect astm-net
   ```

## Success Criteria Verification

| Criterion | How to Verify |
|-----------|---------------|
| SC-001: Multiple analyzers tagged correctly | Run multi-analyzer test, verify distinct IPs |
| SC-002: Query response within 30s | Time the curl command |
| SC-003: Setup in 30 min | Time from git clone to first successful test |
| SC-004: Graceful degradation | Kill socket mid-transfer, verify message still forwarded |
| SC-005: Correct config structure | Verify YAML matches Spring Boot properties |
| SC-006: No regression | Run existing test suite |

