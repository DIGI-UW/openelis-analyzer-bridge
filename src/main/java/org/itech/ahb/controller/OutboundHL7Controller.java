package org.itech.ahb.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.mllp.OutboundMllpClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bridge-proxied HL7 outbound endpoint. OE2 builds the ORM^O01 payload and
 * POSTs it here with target host + port; the bridge handles the MLLP transport
 * via {@link OutboundMllpClient}. Mirrors {@link AnalyzerQueryController} for
 * shape consistency.
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class OutboundHL7Controller {

    private final OutboundMllpClient outboundMllpClient;
    private final AnalyzerRegistryConfig registry;

    public OutboundHL7Controller(OutboundMllpClient outboundMllpClient, AnalyzerRegistryConfig registry) {
        this.outboundMllpClient = outboundMllpClient;
        this.registry = registry;
    }

    @PostMapping("/send-hl7")
    public ResponseEntity<Map<String, Object>> sendHl7(@RequestBody SendHl7Request request) {
        if (request.host == null || request.host.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "host is required"));
        }
        if (request.port == null || request.port < 1 || request.port > 65535) {
            return ResponseEntity.badRequest().body(Map.of("error", "valid port is required (1-65535)"));
        }
        if (request.hl7Message == null || request.hl7Message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "hl7Message is required"));
        }
        // Restrict the destination to a registered analyzer (keyed by sourceId == host).
        // Without this the endpoint would relay arbitrary HL7 to any host:port — an
        // SSRF / internal-port-scan / message-injection primitive, since the bridge is
        // unauthenticated by design. Mirrors OutboundOrderController's registry check.
        if (registry.findAnalyzerEntry(request.host).isEmpty()) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("error", "no registered analyzer for host " + request.host));
        }

        int timeoutMs = request.timeoutMs != null && request.timeoutMs > 0
                ? request.timeoutMs : 30_000;

        long startTime = System.currentTimeMillis();
        OutboundMllpClient.SendResult result = outboundMllpClient.send(
                request.host, request.port, request.hl7Message, timeoutMs);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", result.success);
        response.put("attempts", result.attempts);
        if (result.success) {
            response.put("ack", result.ackMessage);
            log.info("HL7 send to {}:{} succeeded in {} attempt(s)",
                    request.host, request.port, result.attempts);
        } else {
            response.put("error", result.error);
            response.put("ack", result.ackMessage);
            log.warn("HL7 send to {}:{} failed after {} attempt(s): {}",
                    request.host, request.port, result.attempts, result.error);
        }
        response.put("responseTimeMs", System.currentTimeMillis() - startTime);

        return result.success
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(502).body(response);
    }

    public static class SendHl7Request {
        public String host;
        public Integer port;
        public String hl7Message;
        public Integer timeoutMs;
    }
}
