package org.itech.ahb.startup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.util.OeApiClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Pulls the current analyzer registry from OpenELIS on bridge startup.
 *
 * <p>This ensures the bridge has a complete analyzer registry even after a
 * restart, without requiring OE to push or periodic polling. OE is the
 * authoritative source (PostgreSQL). The bridge pulls once on startup.
 *
 * <p>Three sync scenarios:
 * <ol>
 *   <li>OE startup → OE pushes to bridge (existing AnalyzerBridgeStartupRegistrar)</li>
 *   <li>Analyzer CRUD → OE pushes immediately (existing registerWithBridgeAsync)</li>
 *   <li>Bridge restart → bridge pulls from OE (this component)</li>
 * </ol>
 */
@Component
@Slf4j
public class AnalyzerRegistryBootstrap {

    private final AnalyzerRegistryConfig registry;
    private final OeApiClient oeApiClient;
    private final FileWatcher fileWatcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalyzerRegistryBootstrap(
            AnalyzerRegistryConfig registry,
            OeApiClient oeApiClient,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            FileWatcher fileWatcher) {
        this.registry = registry;
        this.oeApiClient = oeApiClient;
        this.fileWatcher = fileWatcher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void pullAnalyzersFromOE() {
        if (!oeApiClient.isConfigured()) {
            log.warn("No OpenELIS URI configured — skipping analyzer registry bootstrap");
            return;
        }

        log.info("Pulling analyzer registry from OE...");

        try {
            String responseBody = oeApiClient.getString("/rest/analyzer/analyzers");
            if (responseBody == null) {
                log.warn("Failed to pull analyzers from OE — registry not bootstrapped");
                return;
            }

            Map<String, Object> body = objectMapper.readValue(
                    responseBody, new TypeReference<>() {
                    });

            Object analyzersObj = body.get("analyzers");
            if (!(analyzersObj instanceof List)) {
                log.warn("Unexpected response format — no 'analyzers' array");
                return;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> analyzers = (List<Map<String, Object>>) analyzersObj;

            LinkedHashMap<String, AnalyzerEntry> newRegistry = new LinkedHashMap<>();
            int fileCount = 0;
            for (Map<String, Object> analyzer : analyzers) {
                Object idObj = analyzer.get("id");
                if (idObj == null) {
                    log.warn("Skipping analyzer with null id: {}", analyzer);
                    continue;
                }
                String id = String.valueOf(idObj);
                if (id.isBlank() || "null".equals(id)) {
                    log.warn("Skipping analyzer with invalid id '{}': {}", id, analyzer);
                    continue;
                }
                String name = (String) analyzer.get("name");
                String ip = (String) analyzer.get("ipAddress");
                String protocol = (String) analyzer.get("protocolVersion");

                if (ip != null && !ip.isBlank()) {
                    AnalyzerEntry entry = new AnalyzerEntry();
                    entry.setId(id);
                    entry.setName(name);
                    entry.setExpectedProtocol(
                            protocol != null && protocol.contains("HL7") ? "HL7" : "ASTM");
                    newRegistry.put(ip, entry);
                }

                String importDir = (String) analyzer.get("importDirectory");
                if (importDir != null && !importDir.isBlank() && fileWatcher != null) {
                    String filePattern = (String) analyzer.get("filePattern");
                    AnalyzerEntry entry = new AnalyzerEntry();
                    entry.setId(id);
                    entry.setName(name);
                    entry.setExpectedProtocol("FILE");
                    if (filePattern != null) {
                        entry.setFilePattern(filePattern);
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, String> colMappings = (Map<String, String>) analyzer.get("columnMappings");
                    if (colMappings != null) {
                        entry.setColumnMappings(colMappings);
                    }
                    String fileFormat = (String) analyzer.get("fileFormat");
                    if (fileFormat != null) {
                        entry.setFileFormat(fileFormat);
                    }
                    String delimiter = (String) analyzer.get("delimiter");
                    if (delimiter != null) {
                        entry.setDelimiter(delimiter);
                    }
                    Object skipRowsObj = analyzer.get("skipRows");
                    if (skipRowsObj instanceof Number) {
                        entry.setSkipRows(((Number) skipRowsObj).intValue());
                    }
                    newRegistry.put(importDir, entry);
                    fileWatcher.addWatchDirectory(
                            Path.of(importDir),
                            filePattern != null ? filePattern : "*",
                            id);
                    fileCount++;
                }
            }

            if (!newRegistry.isEmpty()) {
                AnalyzerRegistryConfig.SyncResult result = registry.syncAll(newRegistry);
                log.info("Bootstrap complete: pulled {} analyzers from OE ({} added, {} updated, {} FILE watch dirs)",
                        result.total(), result.added(), result.updated(), fileCount);
            } else {
                log.info("Bootstrap: no analyzers found in OE — registry empty");
            }

        } catch (java.net.ConnectException e) {
            log.warn("Cannot reach OE — bridge starting without analyzer registry. "
                    + "OE will push registrations when it starts.");
        } catch (Exception e) {
            log.warn("Failed to pull analyzers from OE: {} — bridge starting without registry. "
                    + "OE will push registrations on next CRUD operation.", e.getMessage());
        }
    }
}
