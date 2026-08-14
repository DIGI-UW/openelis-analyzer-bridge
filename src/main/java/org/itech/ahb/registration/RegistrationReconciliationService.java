package org.itech.ahb.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.profile.PortableProfileCatalog;
import org.itech.ahb.profile.ProfileCatalogEntry;
import org.itech.ahb.profile.ProfileCatalogException;
import org.itech.ahb.qc.ControlLotDto;
import org.itech.ahb.qc.QcRule;
import org.springframework.stereotype.Service;

/** Applies versioned OpenELIS desired state to the Bridge runtime registry. */
@Service
@Slf4j
public class RegistrationReconciliationService {

  private final PortableProfileCatalog profiles;
  private final AnalyzerRegistryConfig registry;
  private final FileWatcher fileWatcher;
  private final FileConfig fileConfig;
  private final RegistrationSyncValidator validator;

  public RegistrationReconciliationService(
    PortableProfileCatalog profiles,
    AnalyzerRegistryConfig registry,
    FileWatcher fileWatcher,
    FileConfig fileConfig,
    ObjectMapper objectMapper
  ) {
    this.profiles = profiles;
    this.registry = registry;
    this.fileWatcher = fileWatcher;
    this.fileConfig = fileConfig;
    this.validator = new RegistrationSyncValidator(objectMapper);
  }

  public synchronized RegistrationSyncResult reconcile(JsonNode payload) {
    validator.validate(payload);

    String desiredStateRevision = payload.path("desiredStateRevision").asText();
    Map<String, AnalyzerEntry> previous = registry.getRegisteredAnalyzers();
    Map<String, AnalyzerEntry> accepted = new LinkedHashMap<>();
    List<RegistrationSyncResult.Registration> results = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    Set<String> analyzerIds = new HashSet<>();

    for (JsonNode desired : payload.path("analyzers")) {
      String analyzerId = desired.path("oeAnalyzerId").asText();
      String sourceId = desired.path("sourceId").asText();
      String rejection = null;
      AnalyzerEntry entry = null;
      try {
        if (!analyzerIds.add(analyzerId)) {
          throw new RegistrationSyncException("Duplicate oeAnalyzerId in desired state: " + analyzerId);
        }
        if (accepted.containsKey(sourceId)) {
          throw new RegistrationSyncException("Duplicate sourceId in desired state: " + sourceId);
        }
        entry = buildEntry(desired);
        configureFileWatcher(sourceId, entry);
        accepted.put(sourceId, entry);
      } catch (ProfileCatalogException | RegistrationSyncException exception) {
        rejection = exception.getMessage();
      }

      if (rejection != null) {
        String message = "Analyzer " + analyzerId + ": " + rejection;
        errors.add(message);
        results.add(
          new RegistrationSyncResult.Registration(analyzerId, RegistrationSyncResult.Status.REJECTED, rejection)
        );
      } else {
        AnalyzerEntry old = previous.get(sourceId);
        RegistrationSyncResult.Status status = entry.equals(old)
          ? RegistrationSyncResult.Status.UNCHANGED
          : RegistrationSyncResult.Status.APPLIED;
        results.add(new RegistrationSyncResult.Registration(analyzerId, status, null));
      }
    }

    removeStaleWatchers(previous, accepted);
    AnalyzerRegistryConfig.SyncResult sync = registry.syncAll(accepted);
    int rejected = errors.size();
    int unchanged = accepted.size() - sync.added() - sync.updated();
    RegistrationSyncResult.Counts counts = new RegistrationSyncResult.Counts(
      payload.path("analyzers").size(),
      sync.added(),
      sync.updated(),
      sync.removed(),
      unchanged,
      rejected
    );
    log.info(
      "Applied analyzer desired state {}: {} accepted, {} rejected, {} removed",
      desiredStateRevision,
      accepted.size(),
      rejected,
      sync.removed()
    );
    return new RegistrationSyncResult("1.0", desiredStateRevision, counts, results, errors);
  }

  private AnalyzerEntry buildEntry(JsonNode desired) {
    String profileId = desired.path("profileRef").path("profileId").asText();
    int profileRevision = desired.path("profileRef").path("revision").asInt();
    ProfileCatalogEntry profileEntry = profiles.require(profileId, profileRevision);
    JsonNode profile = profileEntry.profile();

    if (!"ACTIVE".equals(profile.path("status").asText())) {
      throw new RegistrationSyncException("Profile is inactive: " + profileId + " revision " + profileRevision);
    }
    String protocol = desired.path("protocol").asText();
    if (!protocol.equals(profile.path("protocol").asText())) {
      throw new RegistrationSyncException(
        "Registration protocol " + protocol + " does not match profile protocol " + profile.path("protocol").asText()
      );
    }

    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId(desired.path("oeAnalyzerId").asText());
    entry.setName(desired.path("name").asText());
    entry.setExpectedProtocol(protocol);
    entry.setProfileId(profileId);
    entry.setProfileRevision(profileRevision);
    entry.setProfileFingerprint(profileEntry.fingerprint());
    entry.setSiteBindingRevision(desired.path("siteBindingRevision").asText());
    entry.setActive("ACTIVE".equals(desired.path("desiredStatus").asText()));

    JsonNode connection = desired.path("connection");
    entry.setConnectionMode(connection.path("mode").asText());
    entry.setConnectionRole(connection.path("role").asText());
    entry.setConnectionSettings(toValueMap(connection.path("settings")));

    JsonNode identity = profile.path("identity");
    if (identity.hasNonNull("senderPattern")) {
      entry.setIdentifierPattern(identity.path("senderPattern").asText());
    }

    applyTests(profile.path("tests"), entry);
    applyQcIdentification(profile.path("qcIdentification"), entry);
    applyOperationalQc(desired.path("operationalQc"), entry);
    if ("FILE".equals(protocol)) {
      applyFileConfiguration(profile.path("file"), connection.path("settings"), entry);
    }
    return entry;
  }

  private static void applyTests(JsonNode tests, AnalyzerEntry entry) {
    Set<String> mappedCodes = new LinkedHashSet<>();
    Map<String, String> codeToLoinc = new LinkedHashMap<>();
    for (JsonNode test : tests) {
      String code = test.path("analyzerCode").asText();
      mappedCodes.add(code);
      String loinc = loinc(test.path("normalizedCoding"));
      if (loinc != null) {
        codeToLoinc.put(code, loinc);
      }
      for (JsonNode alias : test.path("aliases")) {
        String aliasCode = alias.asText();
        mappedCodes.add(aliasCode);
        if (loinc != null) {
          codeToLoinc.put(aliasCode, loinc);
        }
      }
    }
    entry.setMappedTestCodes(mappedCodes);
    entry.setCodeToLoinc(codeToLoinc);
  }

  private static String loinc(JsonNode coding) {
    if (!coding.isObject() || !"http://loinc.org".equals(coding.path("system").asText())) {
      return null;
    }
    String code = coding.path("code").asText();
    return code.isBlank() ? null : code;
  }

  private static void applyQcIdentification(JsonNode rules, AnalyzerEntry entry) {
    List<QcRule> runtimeRules = new ArrayList<>();
    for (JsonNode rule : rules) {
      runtimeRules.add(
        new QcRule(
          rule.path("ruleType").asText(),
          rule.hasNonNull("targetField") ? rule.path("targetField").asText() : null,
          rule.path("operand").asText()
        )
      );
    }
    entry.setQcRules(runtimeRules);
  }

  private static void applyOperationalQc(JsonNode operationalQc, AnalyzerEntry entry) {
    entry.setOperationalQcContextRevision(operationalQc.path("contextRevision").asText());
    entry.setOperationalQcReady(operationalQc.path("ready").asBoolean());
    Set<String> activeRuleIds = new LinkedHashSet<>();
    operationalQc.path("activeRuleIds").forEach(ruleId -> activeRuleIds.add(ruleId.asText()));
    entry.setActiveOperationalQcRuleIds(activeRuleIds);

    List<ControlLotDto> lots = new ArrayList<>();
    for (JsonNode lot : operationalQc.path("controlLots")) {
      if (lot.path("active").asBoolean()) {
        lots.add(
          new ControlLotDto(
            lot.path("lotNumber").asText(),
            lot.path("controlLevel").asText(),
            null,
            lot.hasNonNull("analyzerCode") ? lot.path("analyzerCode").asText() : null,
            true
          )
        );
      }
    }
    entry.setControlLots(lots);
  }

  private static void applyFileConfiguration(JsonNode file, JsonNode settings, AnalyzerEntry entry) {
    entry.setFileFormat(file.path("format").asText());
    entry.setFilePattern(
      settings.hasNonNull("filePattern") ? settings.path("filePattern").asText() : file.path("filePattern").asText()
    );
    entry.setDelimiter(file.hasNonNull("delimiter") ? file.path("delimiter").asText() : ",");
    entry.setSkipRows(file.path("skipRows").asInt(0));
    Map<String, String> columns = new LinkedHashMap<>();
    file.path("columnMappings").fields().forEachRemaining(field -> columns.put(field.getKey(), field.getValue().asText()));
    entry.setColumnMappings(columns);
  }

  private void configureFileWatcher(String sourceId, AnalyzerEntry entry) {
    if (!"FILE".equals(entry.getExpectedProtocol()) || !entry.isActive()) {
      return;
    }
    if (!fileConfig.isEnabled()) {
      throw new RegistrationSyncException("Active FILE registration requires bridge.file.enabled=true");
    }
    try {
      fileWatcher.addWatchDirectory(Path.of(sourceId), entry.getFilePattern(), entry.getId());
    } catch (InvalidPathException exception) {
      throw new RegistrationSyncException("Invalid FILE source path", exception);
    } catch (IOException exception) {
      throw new RegistrationSyncException("Cannot register FILE watch directory: " + exception.getMessage(), exception);
    }
  }

  private void removeStaleWatchers(Map<String, AnalyzerEntry> previous, Map<String, AnalyzerEntry> accepted) {
    Set<String> activeFileAnalyzerIds = new HashSet<>();
    accepted
      .values()
      .stream()
      .filter(AnalyzerEntry::isActive)
      .filter(entry -> "FILE".equals(entry.getExpectedProtocol()))
      .map(AnalyzerEntry::getId)
      .forEach(activeFileAnalyzerIds::add);
    previous
      .values()
      .stream()
      .map(AnalyzerEntry::getId)
      .filter(id -> !activeFileAnalyzerIds.contains(id))
      .distinct()
      .forEach(fileWatcher::removeWatchDirectoriesByAnalyzerId);
  }

  private static Map<String, Object> toValueMap(JsonNode object) {
    Map<String, Object> values = new LinkedHashMap<>();
    object
      .fields()
      .forEachRemaining(field -> {
        JsonNode value = field.getValue();
        Object scalar;
        if (value.isBoolean()) {
          scalar = value.booleanValue();
        } else if (value.isIntegralNumber()) {
          scalar = value.longValue();
        } else if (value.isFloatingPointNumber()) {
          scalar = value.doubleValue();
        } else {
          scalar = value.asText();
        }
        values.put(field.getKey(), scalar);
      });
    return values;
  }
}
