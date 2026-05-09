package org.itech.ahb.controller;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.qc.QcRule;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for dynamic analyzer registration.
 */
@RestController
@RequestMapping("/api/analyzers")
@Slf4j
public class AnalyzerRegistrationController {

    private final AnalyzerRegistryConfig registry;
    private final FileWatcher fileWatcher;
    private final FileConfig fileConfig;

    public AnalyzerRegistrationController(AnalyzerRegistryConfig registry, FileWatcher fileWatcher, FileConfig fileConfig) {
        this.registry = registry;
        this.fileWatcher = fileWatcher;
        this.fileConfig = fileConfig;
    }

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
        entry.setColumnMappings(request.columnMappings);
        entry.setFileFormat(request.fileFormat);
        entry.setDelimiter(request.delimiter != null ? request.delimiter : ",");
        entry.setSkipRows(request.skipRows != null ? request.skipRows : 0);
        if (request.testMappings != null && !request.testMappings.isEmpty()) {
            entry.setMappedTestCodes(new java.util.LinkedHashSet<>(request.testMappings));
        }
        // QC identification rules from the OE-side analyzer profile
        // (`configDefaults.qcRules`). Without these, FileResultParser falls
        // back to a hardcoded CONTROL_PREFIXES list (CNEG/CPOS/NTC/PTC/...)
        // and any sample-name-prefix convention beyond that — e.g. clinical
        // LPC/HPC for HIV viral load — silently misclassifies QC as patient.
        entry.setQcRules(parseQcRules(request.qcRules));
        entry.setControlLots(parseControlLots(request.controlLots));

        registry.register(request.sourceId, entry);

        boolean fileWatchUpdated = false;
        if (isFileTransport(request.protocol)) {
            if (!fileConfig.isEnabled()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "registered", false,
                        "error", "FILE transport requires bridge.file.enabled=true"));
            }
            try {
                fileWatcher.addWatchDirectory(Path.of(request.sourceId), request.filePattern, request.oeAnalyzerId);
                fileWatchUpdated = true;
            } catch (InvalidPathException e) {
                log.warn("Invalid FILE sourceId path for analyzer {}: {}", request.oeAnalyzerId, request.sourceId);
                return ResponseEntity.badRequest().body(Map.of(
                        "registered", false,
                        "error", "Invalid directory path for FILE sourceId"));
            } catch (IOException e) {
                log.error("Failed to add runtime watch directory {} for analyzer {}", request.sourceId, request.oeAnalyzerId,
                        e);
                return ResponseEntity.internalServerError().body(Map.of(
                        "registered", false,
                        "error", "Bridge failed to register FILE watch directory: " + e.getMessage()));
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("registered", true);
        response.put("oeAnalyzerId", request.oeAnalyzerId);
        response.put("sourceId", request.sourceId);
        response.put("protocol", request.protocol);
        response.put("fileWatchUpdated", fileWatchUpdated);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{oeAnalyzerId}")
    public ResponseEntity<Map<String, Object>> unregister(@PathVariable String oeAnalyzerId) {
        boolean removed = registry.unregisterByAnalyzerId(oeAnalyzerId);
        int removedWatchDirs = fileWatcher.removeWatchDirectoriesByAnalyzerId(oeAnalyzerId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("removed", removed || removedWatchDirs > 0);
        response.put("oeAnalyzerId", oeAnalyzerId);
        response.put("removedWatchDirectories", removedWatchDirs);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Map<String, AnalyzerEntry>> list() {
        return ResponseEntity.ok(registry.getRegisteredAnalyzers());
    }

    /**
     * Full-state sync: OE pushes its complete analyzer registry.
     * Replaces the bridge's in-memory registry entirely (idempotent).
     * This handles bridge restarts (next OE sync restores full state)
     * and OE restarts (startup sync pushes everything).
     */
    @PutMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(@RequestBody java.util.List<RegistrationRequest> registrations) {
        // Capture previous analyzer IDs to detect removals for file watcher cleanup
        java.util.Set<String> previousAnalyzerIds = new java.util.HashSet<>();
        for (AnalyzerEntry entry : registry.getRegisteredAnalyzers().values()) {
            previousAnalyzerIds.add(entry.getId());
        }

        Map<String, AnalyzerEntry> newRegistry = new java.util.LinkedHashMap<>();
        java.util.Set<String> newAnalyzerIds = new java.util.HashSet<>();
        int fileWatchUpdates = 0;

        for (RegistrationRequest req : registrations) {
            if (req.oeAnalyzerId == null || req.oeAnalyzerId.isBlank()
                    || req.sourceId == null || req.sourceId.isBlank()) {
                continue;
            }
            AnalyzerEntry entry = new AnalyzerEntry();
            entry.setId(req.oeAnalyzerId);
            entry.setName(req.name);
            entry.setExpectedProtocol(req.protocol);
            entry.setFilePattern(req.filePattern);
            entry.setColumnMappings(req.columnMappings);
            entry.setFileFormat(req.fileFormat);
            entry.setDelimiter(req.delimiter != null ? req.delimiter : ",");
            entry.setSkipRows(req.skipRows != null ? req.skipRows : 0);
            if (req.testMappings != null && !req.testMappings.isEmpty()) {
                entry.setMappedTestCodes(new java.util.LinkedHashSet<>(req.testMappings));
            }
            entry.setQcRules(parseQcRules(req.qcRules));
            entry.setControlLots(parseControlLots(req.controlLots));
            newRegistry.put(req.sourceId, entry);
            newAnalyzerIds.add(req.oeAnalyzerId);

            // Update file watchers for FILE transport
            if (isFileTransport(req.protocol) && fileConfig.isEnabled()) {
                try {
                    fileWatcher.addWatchDirectory(Path.of(req.sourceId), req.filePattern, req.oeAnalyzerId);
                    fileWatchUpdates++;
                } catch (InvalidPathException e) {
                    log.warn("Invalid path for file watcher {} during sync: {}", req.oeAnalyzerId, req.sourceId);
                } catch (IOException e) {
                    log.warn("Failed to update file watcher for {} during sync: {}", req.oeAnalyzerId, e.getMessage());
                }
            }
        }

        // Remove stale file watchers for analyzers no longer in the registry
        int removedWatchers = 0;
        for (String previousId : previousAnalyzerIds) {
            if (!newAnalyzerIds.contains(previousId)) {
                int removed = fileWatcher.removeWatchDirectoriesByAnalyzerId(previousId);
                if (removed > 0) {
                    removedWatchers += removed;
                    log.info("Removed {} stale file watcher(s) for analyzer {} during sync", removed, previousId);
                }
            }
        }

        AnalyzerRegistryConfig.SyncResult result = registry.syncAll(newRegistry);

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("synced", result.total());
        response.put("added", result.added());
        response.put("updated", result.updated());
        response.put("removed", result.removed());
        response.put("fileWatchUpdates", fileWatchUpdates);
        response.put("removedWatchers", removedWatchers);
        return ResponseEntity.ok(response);
    }

    /**
     * FILE analyzers may register with {@code protocol=FILE} or legacy {@code CSV}.
     */
    private static boolean isFileTransport(String protocol) {
        return protocol != null
                && ("FILE".equalsIgnoreCase(protocol) || "CSV".equalsIgnoreCase(protocol));
    }

    public static class RegistrationRequest {
        public String oeAnalyzerId;
        public String sourceId;
        public String name;
        public String protocol;
        public String filePattern;
        public java.util.Map<String, String> columnMappings;
        public String fileFormat;
        public String delimiter;
        public Integer skipRows;
        public java.util.List<String> testMappings;
        // QC identification rules pushed from OE per analyzer profile
        // (`configDefaults.qcRules`). Each map carries
        // {ruleType, targetField?, operand}. Mirrors the JSON shape that
        // AnalyzerRegistryBootstrap.parseQcRules consumes at startup.
        public java.util.List<java.util.Map<String, Object>> qcRules;
        // Active QC control lots pushed from OE so parsers can attach
        // lotNumber to QC samples whose inbound message embeds the lot
        // identifier. Each map carries {lotNumber, controlLevel?, testId?}.
        public java.util.List<java.util.Map<String, Object>> controlLots;
    }

    /**
     * Parse the raw JSON `qcRules` list (as received from OE's registration
     * push) into typed {@link QcRule} records. Mirrors the algorithm used
     * by {@code AnalyzerRegistryBootstrap.parseQcRules} so startup-pulled
     * and runtime-pushed registrations produce identical entries.
     */
    private static List<QcRule> parseQcRules(java.util.List<java.util.Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<QcRule> rules = new ArrayList<>(raw.size());
        for (java.util.Map<String, Object> ruleMap : raw) {
            if (ruleMap == null) continue;
            String ruleType = (String) ruleMap.get("ruleType");
            String targetField = (String) ruleMap.get("targetField");
            String operand = (String) ruleMap.get("operand");
            if (ruleType != null && operand != null) {
                rules.add(new QcRule(ruleType, targetField, operand));
            }
        }
        return rules;
    }

    /**
     * Parse the raw JSON {@code controlLots} list (from OE's registration
     * push) into typed {@link org.itech.ahb.qc.ControlLotDto} records. Lots
     * without a non-blank lotNumber are dropped.
     */
    private static List<org.itech.ahb.qc.ControlLotDto> parseControlLots(
            java.util.List<java.util.Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<org.itech.ahb.qc.ControlLotDto> lots = new ArrayList<>(raw.size());
        for (java.util.Map<String, Object> lotMap : raw) {
            if (lotMap == null) continue;
            String lotNumber = (String) lotMap.get("lotNumber");
            if (lotNumber == null || lotNumber.isBlank()) continue;
            String controlLevel = (String) lotMap.get("controlLevel");
            Object testIdObj = lotMap.get("testId");
            Integer testId = (testIdObj instanceof Number) ? ((Number) testIdObj).intValue() : null;
            lots.add(new org.itech.ahb.qc.ControlLotDto(lotNumber, controlLevel, testId));
        }
        return lots;
    }
}
