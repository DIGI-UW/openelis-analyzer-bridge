package org.itech.ahb.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.file.FileWatcher;
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
 */
@RestController
@RequestMapping("/api/analyzers")
@Slf4j
public class AnalyzerRegistrationController {

    private final AnalyzerRegistryConfig registry;
    private final FileWatcher fileWatcher;

    public AnalyzerRegistrationController(AnalyzerRegistryConfig registry, FileWatcher fileWatcher) {
        this.registry = registry;
        this.fileWatcher = fileWatcher;
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

        registry.register(request.sourceId, entry);

        boolean fileWatchUpdated = false;
        if ("FILE".equalsIgnoreCase(request.protocol)) {
            try {
                fileWatcher.addWatchDirectory(Path.of(request.sourceId), request.filePattern, request.oeAnalyzerId);
                fileWatchUpdated = true;
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

    public static class RegistrationRequest {
        public String oeAnalyzerId;
        public String sourceId;
        public String name;
        public String protocol;
        public String filePattern;
    }
}
