package org.itech.ahb.startup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.qc.QcRule;
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

                @SuppressWarnings("unchecked")
                List<String> rawMappings = (List<String>) analyzer.get("testMappings");
                java.util.Set<String> mappedTestCodes =
                        (rawMappings != null && !rawMappings.isEmpty())
                                ? new java.util.LinkedHashSet<>(rawMappings)
                                : java.util.Collections.emptySet();

                if (ip != null && !ip.isBlank()) {
                    AnalyzerEntry entry = new AnalyzerEntry();
                    entry.setId(id);
                    entry.setName(name);
                    entry.setExpectedProtocol(
                            protocol != null && protocol.contains("HL7") ? "HL7" : "ASTM");
                    entry.setMappedTestCodes(mappedTestCodes);
                    entry.setQcRules(parseQcRules(analyzer));
                    entry.setControlLots(parseControlLots(analyzer));
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
                    entry.setDelimiter(delimiter != null ? delimiter : ",");
                    Object skipRowsObj = analyzer.get("skipRows");
                    entry.setSkipRows(skipRowsObj instanceof Number ? ((Number) skipRowsObj).intValue() : 0);
                    entry.setMappedTestCodes(mappedTestCodes);
                    @SuppressWarnings("unchecked")
                    Map<String, List<String>> synonyms =
                            (Map<String, List<String>>) analyzer.get("scannerSynonyms");
                    if (synonyms != null && !synonyms.isEmpty()) {
                        entry.setScannerSynonyms(synonyms);
                    } else {
                        Map<String, List<String>> fallback = defaultScannerSynonymsForAnalyzerName(name);
                        if (!fallback.isEmpty()) {
                            entry.setScannerSynonyms(fallback);
                        }
                    }
                    entry.setQcRules(parseQcRules(analyzer));
                    entry.setControlLots(parseControlLots(analyzer));
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

    /**
     * Hardcoded fallback synonym table by analyzer-name substring match.
     * Fluorocycler XT's result files use {@code HIV-1} and
     * {@code GENERIC_HIV_CV} vocabulary but OE's test code is
     * {@code VIH-1}; without translation the scanner returns
     * NoDeclaration on valid files.
     */
    private Map<String, List<String>> defaultScannerSynonymsForAnalyzerName(String analyzerName) {
        if (analyzerName == null) return java.util.Collections.emptyMap();
        String lower = analyzerName.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("fluorocycler")) {
            Map<String, List<String>> syn = new java.util.LinkedHashMap<>();
            syn.put("VIH-1", java.util.List.of("VIH-1", "HIV-1", "GENERIC_HIV_CV"));
            syn.put("CHIKV", java.util.List.of("CHIKV", "Chikungunya"));
            syn.put("DENV", java.util.List.of("DENV", "Dengue"));
            syn.put("ZIKV", java.util.List.of("ZIKV", "Zika"));
            return syn;
        }
        return java.util.Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<QcRule> parseQcRules(Map<String, Object> analyzer) {
        Object qcRulesObj = analyzer.get("qcRules");
        if (!(qcRulesObj instanceof List<?>)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> rawRules = (List<Map<String, Object>>) qcRulesObj;
        List<QcRule> rules = new ArrayList<>(rawRules.size());
        for (Map<String, Object> ruleMap : rawRules) {
            String ruleType = (String) ruleMap.get("ruleType");
            String targetField = (String) ruleMap.get("targetField");
            String operand = (String) ruleMap.get("operand");
            if (ruleType != null && operand != null) {
                rules.add(new QcRule(ruleType, targetField, operand));
            }
        }
        return rules;
    }

    @SuppressWarnings("unchecked")
    private List<org.itech.ahb.qc.ControlLotDto> parseControlLots(Map<String, Object> analyzer) {
        Object lotsObj = analyzer.get("controlLots");
        if (!(lotsObj instanceof List<?>)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> rawLots = (List<Map<String, Object>>) lotsObj;
        List<org.itech.ahb.qc.ControlLotDto> lots = new ArrayList<>(rawLots.size());
        for (Map<String, Object> lotMap : rawLots) {
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
