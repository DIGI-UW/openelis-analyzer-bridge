package org.itech.ahb.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.mllp.OutboundMllpClient;
import org.itech.ahb.order.OrderBuilder;
import org.itech.ahb.order.OutboundAstmClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bridge-owned outbound order dispatch. OpenELIS identifies an active Bridge
 * connection and supplies a LOINC-coded clinical order. The Bridge resolves
 * protocol and network details from that connection, translates each LOINC to
 * the analyzer's test code, builds the protocol message, and dispatches it.
 *
 * <p>OE2 never constructs analyzer protocol messages or handles analyzer codes
 * — it stays analyzer-agnostic and speaks LOINC.
 */
@RestController
@RequestMapping("/api")
@Slf4j
public class OutboundOrderController {

    private static final int DEFAULT_TIMEOUT_MS = 30000;

    private final AnalyzerRuntimeRegistry registry;
    private final OutboundMllpClient outboundMllpClient;
    private final OutboundAstmClient outboundAstmClient;

    public OutboundOrderController(AnalyzerRuntimeRegistry registry,
            OutboundMllpClient outboundMllpClient, OutboundAstmClient outboundAstmClient) {
        this.registry = registry;
        this.outboundMllpClient = outboundMllpClient;
        this.outboundAstmClient = outboundAstmClient;
    }

    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> sendOrder(@RequestBody OrderRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (request.connectionId == null || request.connectionId.isBlank()) {
            response.put("dispatched", false);
            response.put("error", "connectionId is required");
            return ResponseEntity.badRequest().body(response);
        }
        if (request.order == null) {
            response.put("dispatched", false);
            response.put("error", "order is required");
            return ResponseEntity.badRequest().body(response);
        }
        if (request.order.loincCodes == null || request.order.loincCodes.isEmpty()) {
            response.put("dispatched", false);
            response.put("error", "loincCodes are required");
            return ResponseEntity.badRequest().body(response);
        }

        AnalyzerEntry entry = registry.findAnalyzerEntryByConnectionId(request.connectionId).orElse(null);
        if (entry == null) {
            response.put("dispatched", false);
            response.put("error", "no active Bridge connection " + request.connectionId);
            return ResponseEntity.unprocessableEntity().body(response);
        }
        String host = entry.getOutboundHost();
        int port = entry.getOutboundPort();
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            response.put("dispatched", false);
            response.put("error", "Bridge connection " + request.connectionId
                    + " has no outbound endpoint");
            return ResponseEntity.unprocessableEntity().body(response);
        }

        List<String> codes = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        for (String loinc : request.order.loincCodes) {
            String code = entry.getCodeForLoinc(loinc);
            if (code != null) {
                codes.add(code);
            } else {
                unmapped.add(loinc);
            }
        }
        response.put("unmapped", unmapped);

        if (codes.isEmpty()) {
            response.put("dispatched", false);
            response.put("error", "no LOINC resolved for Bridge connection " + request.connectionId);
            return ResponseEntity.unprocessableEntity().body(response);
        }

        String protocol = entry.getExpectedProtocol() == null ? "" : entry.getExpectedProtocol().toUpperCase();
        if (!protocol.startsWith("HL7") && !protocol.startsWith("ASTM")) {
            response.put("dispatched", false);
            response.put("error", "Bridge connection " + request.connectionId
                    + " does not support outbound orders");
            return ResponseEntity.badRequest().body(response);
        }
        boolean dispatched;
        String error = null;

        if (protocol.startsWith("HL7")) {
            String orm = OrderBuilder.buildHl7Orm(request.order.accessionNumber, request.order.patientId, codes);
            OutboundMllpClient.SendResult r = outboundMllpClient.send(host, port, orm, DEFAULT_TIMEOUT_MS);
            dispatched = r.success;
            error = r.error;
            response.put("protocol", "HL7");
        } else {
            String astm = OrderBuilder.buildAstm(request.order.accessionNumber, request.order.patientId, codes);
            List<String> records = new ArrayList<>();
            for (String rec : astm.split("\r")) {
                if (!rec.isBlank()) records.add(rec);
            }
            dispatched = outboundAstmClient.send(host, port, records, DEFAULT_TIMEOUT_MS);
            if (!dispatched) error = "ASTM send failed";
            response.put("protocol", "ASTM");
        }

        response.put("connectionId", request.connectionId);
        response.put("dispatched", dispatched);
        if (!dispatched) {
            response.put("error", error);
            log.warn("Outbound order for Bridge connection {} failed: {}", request.connectionId, error);
            return ResponseEntity.status(502).body(response);
        }
        log.info("Outbound order dispatched for Bridge connection {} ({})",
                request.connectionId, response.get("protocol"));
        return ResponseEntity.ok(response);
    }

    public static class OrderRequest {
        public String connectionId;
        public ClinicalOrder order;
    }

    public static class ClinicalOrder {
        public String accessionNumber;
        public String patientId;
        public List<String> loincCodes;
    }
}
