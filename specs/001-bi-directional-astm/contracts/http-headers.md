# HTTP Header Contract: X-Source-Analyzer-IP

**Feature**: 001-bi-directional-astm  
**Date**: 2025-12-03  
**Version**: 1.0.0

## Overview

This document specifies the `X-Source-Analyzer-IP` HTTP header added to requests 
forwarded from the ASTM-HTTP Bridge to OpenELIS.

## Header Specification

### X-Source-Analyzer-IP

| Property | Value |
|----------|-------|
| **Header Name** | `X-Source-Analyzer-IP` |
| **Direction** | ASTM → HTTP (analyzer to OpenELIS) |
| **Required** | No |
| **Format** | IPv4 or IPv6 address string |
| **Examples** | `192.168.1.10`, `10.0.0.5`, `::1`, `2001:db8::1` |

### Description

The `X-Source-Analyzer-IP` header contains the IP address of the medical analyzer 
that sent the ASTM message through the bridge. This enables OpenELIS to identify 
which analyzer sent the message and apply the correct field mappings.

### When Present

The header is included when:
- An analyzer connects to the bridge via TCP
- The bridge successfully extracts the source IP from the socket
- The message is forwarded to OpenELIS via HTTP POST

### When Absent

The header may be absent when:
- Socket state prevents IP extraction (closed, null)
- An error occurs during extraction
- The bridge is configured to omit the header (future configuration option)

When the header is absent, OpenELIS should fall back to other identification methods:
1. Parse analyzer name from ASTM H-segment
2. Use plugin-based analyzer matching

## HTTP Request Examples

### IPv4 Address

```http
POST /api/OpenELIS-Global/analyzer/astm HTTP/1.1
Host: openelis.example.org:8443
Content-Type: text/plain
X-Source-Analyzer-IP: 192.168.1.10

H|\^&|||Analyzer^Model^1.0|||||||LIS01-A|P|1|20231201120000
P|1||PatientID||LastName^FirstName||19900101|M
O|1|SampleID||^^^TestCode|||20231201120000
R|1|^^^TestCode|42.5|mg/dL|||N||F|||20231201120000
L|1|N
```

### IPv6 Address

```http
POST /api/OpenELIS-Global/analyzer/astm HTTP/1.1
Host: openelis.example.org:8443
Content-Type: text/plain
X-Source-Analyzer-IP: 2001:db8::1

H|\^&|||Analyzer^Model^1.0|||||||LIS01-A|P|1|20231201120000
...
```

### Without Header (Graceful Degradation)

```http
POST /api/OpenELIS-Global/analyzer/astm HTTP/1.1
Host: openelis.example.org:8443
Content-Type: text/plain

H|\^&|||Analyzer^Model^1.0|||||||LIS01-A|P|1|20231201120000
...
```

## OpenELIS Integration

### Header Extraction

OpenELIS `AnalyzerImportController` should extract the header:

```java
@PostMapping("/analyzer/astm")
public void doPost(HttpServletRequest request, HttpServletResponse response) {
    // Primary: X-Source-Analyzer-IP header from bridge
    String sourceIp = request.getHeader("X-Source-Analyzer-IP");
    
    // Fallback: Direct connection IP (when not using bridge)
    if (sourceIp == null || sourceIp.isEmpty()) {
        sourceIp = request.getRemoteAddr();
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            sourceIp = forwardedFor.split(",")[0].trim();
        }
    }
    
    // Use sourceIp for analyzer identification
}
```

### Analyzer Lookup

OpenELIS should use the source IP to find the analyzer configuration:

```java
Optional<AnalyzerConfiguration> config = configService.getByIpAddress(sourceIp);
if (config.isPresent()) {
    // Apply analyzer-specific field mappings
}
```

## Backward Compatibility

### Bridge Version < 2.4.0

Older bridge versions do not include this header. OpenELIS MUST gracefully 
handle requests without the header by:
1. Parsing analyzer info from ASTM H-segment
2. Using plugin-based matching
3. Logging a warning for debugging

### Migration Path

1. Deploy updated bridge with header support
2. Update OpenELIS to extract and use header
3. Register analyzers in OpenELIS with IP addresses
4. Monitor logs for successful header-based identification

## Security Considerations

### Trust Model

- The header is set by the bridge, not the analyzer
- The bridge is trusted infrastructure
- The header reflects the TCP socket's remote address
- OpenELIS should trust this header when requests come from known bridge IPs

### Header Spoofing

- Analyzers cannot spoof this header (bridge extracts from socket)
- Malicious HTTP clients could add the header directly to OpenELIS
- OpenELIS should verify requests come from authorized bridge IPs

## Testing

### Verification

```bash
# Start bridge with debug logging
docker run -e LOGGING_LEVEL_ORG_ITECH=DEBUG astm-http-bridge

# Send test message via mock server
python server.py --push http://localhost:12001 --analyzer-type HEMATOLOGY

# Check OpenELIS logs for header
docker logs openelis-global | grep "X-Source-Analyzer-IP"
```

### Test Cases

| Scenario | Expected Header |
|----------|-----------------|
| IPv4 analyzer connects | `X-Source-Analyzer-IP: 192.168.1.10` |
| IPv6 analyzer connects | `X-Source-Analyzer-IP: 2001:db8::1` |
| Localhost test | `X-Source-Analyzer-IP: 127.0.0.1` |
| Socket error | Header absent, warning logged |
| Multiple analyzers | Each message has correct source IP |

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2025-12-03 | Initial specification |

