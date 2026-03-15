package org.itech.ahb.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for dynamic analyzer registration.
 * <p>
 * OpenELIS calls these endpoints when an analyzer is created, updated,
 * or deleted in the dashboard. The bridge uses the registry to tag incoming
 * traffic with the correct OE analyzer ID ({@code X-Analyzer-Id} header)
 * before forwarding to OpenELIS.
 * </p>
 * <p>
 * Registration binds a transport source (IP address, file watch directory,
 * serial port) to an OE analyzer ID. The bridge only needs the minimum
 * transport config — all business logic (test mappings, QC rules, etc.)
 * stays in OpenELIS.
 * </p>
 */
@RestController
@RequestMapping("/api/analyzers")
@Slf4j
public class AnalyzerRegistrationController {

    private final AnalyzerRegistryConfig registry;

    public AnalyzerRegistrationController(AnalyzerRegistryConfig registry) {
        this.registry = registry;
    }

    /**
     * Register an analyzer's transport binding.
     * <p>
     * Called by OpenELIS when an analyzer is created or updated. The bridge
     * stores the mapping and uses it to tag all traffic from the registered
     * source with the OE analyzer ID.
     * </p>
     *
     * @param request registration payload with transport config
     * @return registration confirmation
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegistrationRequest request) {
        if (request.oeAnalyzerId == null || request.oeAnalyzerId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "registered", false,
                    "error", "oeAnalyzerId is required"));
        }
        if (request.sourceId == null || request.sourceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "registered", false,
                    "error", "sourceId is required (IP address, directory path, or serial port)"));
        }

        AnalyzerEntry entry = new AnalyzerEntry();
        entry.setId(request.oeAnalyzerId);
        entry.setName(request.name);
        entry.setExpectedProtocol(request.protocol);
        entry.setFilePattern(request.filePattern);

        registry.register(request.sourceId, entry);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("registered", true);
        response.put("oeAnalyzerId", request.oeAnalyzerId);
        response.put("sourceId", request.sourceId);
        response.put("protocol", request.protocol);
        return ResponseEntity.ok(response);
    }

    /**
     * Unregister an analyzer by OE analyzer ID.
     * Removes all source mappings that point to this analyzer.
     */
    @DeleteMapping("/{oeAnalyzerId}")
    public ResponseEntity<Map<String, Object>> unregister(@PathVariable String oeAnalyzerId) {
        boolean removed = registry.unregisterByAnalyzerId(oeAnalyzerId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("removed", removed);
        response.put("oeAnalyzerId", oeAnalyzerId);
        return ResponseEntity.ok(response);
    }

    /**
     * List all registered analyzers.
     */
    @GetMapping
    public ResponseEntity<Map<String, AnalyzerEntry>> list() {
        return ResponseEntity.ok(registry.getRegisteredAnalyzers());
    }

    /**
     * Registration request payload.
     */
    public static class RegistrationRequest {
        /** OE analyzer ID (from the analyzer table in OpenELIS) */
        public String oeAnalyzerId;

        /** Source identifier: IP address for TCP, directory path for FILE, serial port for SERIAL */
        public String sourceId;

        /** Human-readable analyzer name */
        public String name;

        /** Expected protocol: ASTM, HL7, CSV */
        public String protocol;

        /** Optional file pattern for FILE transport (glob or regex) */
        public String filePattern;
    }
}
