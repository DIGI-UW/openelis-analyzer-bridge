package org.itech.ahb.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.mllp.OutboundMllpClient;
import org.itech.ahb.order.OrderBuilder;
import org.itech.ahb.order.OutboundAstmClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bridge-owned outbound order dispatch. OE2 sends a LOINC-coded order
 * ({@code {host, port, protocol, accessionNumber, loincCodes[]}}); the bridge
 * translates each LOINC → the analyzer's own test code via the registered
 * {@code code↔LOINC} map (pushed from OE2's profile), builds the protocol
 * message ({@link OrderBuilder}), and dispatches it (HL7 via
 * {@link OutboundMllpClient}, ASTM via {@link OutboundAstmClient}).
 *
 * <p>OE2 never constructs analyzer protocol messages or handles analyzer codes
 * — it stays analyzer-agnostic and speaks LOINC.
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class OutboundOrderController {

    private final AnalyzerRegistryConfig registry;
    private final OutboundMllpClient outboundMllpClient;
    private final OutboundAstmClient outboundAstmClient;

    public OutboundOrderController(AnalyzerRegistryConfig registry,
            OutboundMllpClient outboundMllpClient, OutboundAstmClient outboundAstmClient) {
        this.registry = registry;
        this.outboundMllpClient = outboundMllpClient;
        this.outboundAstmClient = outboundAstmClient;
    }

    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> sendOrder(@RequestBody OrderRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (request.host == null || request.host.isBlank() || request.port == null) {
            response.put("dispatched", false);
            response.put("error", "host and port are required");
            return ResponseEntity.badRequest().body(response);
        }
        if (request.port < 1 || request.port > 65535) {
            response.put("dispatched", false);
            response.put("error", "port must be in 1-65535");
            return ResponseEntity.badRequest().body(response);
        }
        if (request.loincCodes == null || request.loincCodes.isEmpty()) {
            response.put("dispatched", false);
            response.put("error", "loincCodes are required");
            return ResponseEntity.badRequest().body(response);
        }

        // Resolve the registered analyzer (keyed by sourceId == host) and its
        // code↔LOINC map. Without it the bridge can't translate → 422.
        AnalyzerEntry entry = registry.findAnalyzerEntry(request.host).orElse(null);
        if (entry == null) {
            response.put("dispatched", false);
            response.put("error", "no registered analyzer (mapping) for host " + request.host);
            return ResponseEntity.unprocessableEntity().body(response);
        }

        List<String> codes = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        for (String loinc : request.loincCodes) {
            String code = entry.getCodeForLoinc(loinc);
            if (code != null) {
                codes.add(code);
            } else {
                unmapped.add(loinc);
            }
        }
        response.put("codes", codes);
        response.put("unmapped", unmapped);

        if (codes.isEmpty()) {
            response.put("dispatched", false);
            response.put("error", "no LOINC resolved to an analyzer code for " + request.host);
            return ResponseEntity.unprocessableEntity().body(response);
        }

        int timeout = request.timeoutMs != null && request.timeoutMs > 0 ? request.timeoutMs : 30000;
        String protocol = request.protocol == null ? "" : request.protocol.toUpperCase();
        // Reject anything that isn't explicitly HL7 or ASTM rather than silently
        // dispatching an unknown/typo'd protocol as ASTM.
        if (!protocol.startsWith("HL7") && !protocol.startsWith("ASTM")) {
            response.put("dispatched", false);
            response.put("error", "unsupported protocol '" + request.protocol + "' (expected HL7 or ASTM)");
            return ResponseEntity.badRequest().body(response);
        }
        boolean dispatched;
        String error = null;

        if (protocol.startsWith("HL7")) {
            String orm = OrderBuilder.buildHl7Orm(request.accessionNumber, request.patientId, codes);
            OutboundMllpClient.SendResult r = outboundMllpClient.send(request.host, request.port, orm, timeout);
            dispatched = r.success;
            error = r.error;
            response.put("protocol", "HL7");
        } else {
            String astm = OrderBuilder.buildAstm(request.accessionNumber, request.patientId, codes);
            List<String> records = new ArrayList<>();
            for (String rec : astm.split("\r")) {
                if (!rec.isBlank()) records.add(rec);
            }
            dispatched = outboundAstmClient.send(request.host, request.port, records, timeout);
            if (!dispatched) error = "ASTM send failed";
            response.put("protocol", "ASTM");
        }

        response.put("dispatched", dispatched);
        if (!dispatched) {
            response.put("error", error);
            log.warn("Outbound order to {}:{} failed: {}", request.host, request.port, error);
            return ResponseEntity.status(502).body(response);
        }
        log.info("Outbound order dispatched to {}:{} ({}) codes={}", request.host, request.port,
                response.get("protocol"), codes);
        return ResponseEntity.ok(response);
    }

    public static class OrderRequest {
        public String host;
        public Integer port;
        public String protocol;
        public String accessionNumber;
        public String patientId;
        public List<String> loincCodes;
        public Integer timeoutMs;
    }
}
