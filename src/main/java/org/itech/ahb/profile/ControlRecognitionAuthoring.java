package org.itech.ahb.profile;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Safe authoring projection for profile-owned control-result recognition. */
public final class ControlRecognitionAuthoring {

  private static final Pattern RULE_KEY = Pattern.compile("^[a-z0-9][a-z0-9._-]+$");
  private final ObjectMapper objectMapper;
  private final ProfileFingerprintService fingerprints = new ProfileFingerprintService();

  public ControlRecognitionAuthoring(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public View inspect(JsonNode profile) {
    String protocol = protocol(profile);
    Map<String, Source> sources = sources(profile, protocol);
    JsonNode recognitionNode = profile == null ? null : profile.get("controlResultRecognition");
    if (recognitionNode == null || recognitionNode.isNull() || recognitionNode.isMissingNode()) {
      return new View(
        null,
        false,
        "Control-result recognition has not been configured.",
        List.of(),
        List.copyOf(sources.values())
      );
    }

    ControlResultRecognition recognition;
    try {
      recognition = ControlResultRecognition.fromProfile(recognitionNode);
    } catch (IllegalArgumentException exception) {
      throw new ProfileCatalogException("Control-result recognition is invalid: " + exception.getMessage());
    }
    if (recognition.mode() == ControlResultRecognition.Mode.NONE) {
      return new View(
        "NONE",
        true,
        "This analyzer interface transports no control results.",
        List.of(),
        List.copyOf(sources.values())
      );
    }

    List<Condition> conditions = recognition
      .rules()
      .stream()
      .map(rule -> condition(rule, protocol, sources))
      .toList();
    return new View(
      "RULES",
      false,
      "Any listed condition identifies a control result.",
      conditions,
      List.copyOf(sources.values())
    );
  }

  public DraftView inspect(ProfileDraft draft) {
    return new DraftView(
      draft.draftId(),
      draft.kind().name(),
      draft.baseProfileId(),
      draft.baseRevision(),
      draft.profile().path("profileMeta").path("displayName").asText(),
      draft.updatedBy(),
      draft.updatedAt().toString(),
      draft.validationIssues(),
      inspect(draft.profile())
    );
  }

  public ObjectNode apply(ObjectNode profile, Update update) {
    if (profile == null) {
      throw new ProfileCatalogException("profile is required");
    }
    if (update == null || update.mode() == null) {
      throw new ProfileCatalogException("Control-result recognition mode is required");
    }

    ObjectNode changed = profile.deepCopy();
    if ("NONE".equals(update.mode())) {
      if (!update.affirmedNoControlResults()) {
        throw new ProfileCatalogException(
          "The profile author must affirm that this interface transports no control results"
        );
      }
      if (!update.conditions().isEmpty()) {
        throw new ProfileCatalogException("NONE recognition cannot contain conditions");
      }
      ObjectNode recognition = objectMapper.createObjectNode();
      recognition.put("mode", "NONE");
      recognition.put("affirmedNoControlResults", true);
      changed.set("controlResultRecognition", recognition);
      return changed;
    }
    if (!"RULES".equals(update.mode())) {
      throw new ProfileCatalogException("Unsupported control-result recognition mode " + update.mode());
    }
    if (update.affirmedNoControlResults()) {
      throw new ProfileCatalogException("RULES recognition cannot affirm that no controls are transported");
    }
    if (update.conditions().isEmpty()) {
      throw new ProfileCatalogException("RULES recognition requires at least one condition");
    }

    String protocol = protocol(profile);
    Map<String, Source> sources = sources(profile, protocol);
    Map<String, JsonNode> existing = existingRules(profile);
    Set<String> usedKeys = new LinkedHashSet<>();
    ObjectNode rules = objectMapper.createObjectNode();
    int generatedIndex = 1;
    for (ConditionInput input : update.conditions()) {
      if (input == null || input.kind() == null) {
        throw new ProfileCatalogException("Every recognition condition requires a kind");
      }
      String key = nullableText(input.key());
      if (key == null) {
        do {
          key = "condition-" + generatedIndex++;
        } while (existing.containsKey(key) || usedKeys.contains(key));
      }
      if (!RULE_KEY.matcher(key).matches() || !usedKeys.add(key)) {
        throw new ProfileCatalogException("Recognition condition keys must be valid and unique");
      }
      rules.set(key, authoredRule(key, input, sources, existing));
    }

    ObjectNode recognition = objectMapper.createObjectNode();
    recognition.put("mode", "RULES");
    recognition.set("rules", rules);
    changed.set("controlResultRecognition", recognition);
    return changed;
  }

  private ObjectNode authoredRule(
    String key,
    ConditionInput input,
    Map<String, Source> sources,
    Map<String, JsonNode> existing
  ) {
    if ("CONFIGURED_SPECIMEN_ID_PATTERN".equals(input.kind())) {
      JsonNode current = existing.get(key);
      if (current == null || !"SPECIMEN_ID_PATTERN".equals(current.path("ruleType").asText())) {
        throw new ProfileCatalogException("Configured pattern conditions can only preserve an existing rule");
      }
      return ((ObjectNode) current).deepCopy();
    }

    ObjectNode rule = objectMapper.createObjectNode();
    String value = requireText(input.value(), "Recognition condition value");
    switch (input.kind()) {
      case "SPECIMEN_ID_STARTS_WITH" -> {
        rule.put("ruleType", "SPECIMEN_ID_PREFIX");
        rule.put("operand", value);
      }
      case "FIELD_VALUE_EQUALS", "FIELD_VALUE_CONTAINS" -> {
        Source source = sources.get(input.sourceKey());
        if (source == null) {
          throw new ProfileCatalogException("Select a valid recognition condition source");
        }
        rule.put(
          "ruleType",
          "FIELD_VALUE_EQUALS".equals(input.kind()) ? "FIELD_EQUALS" : "FIELD_CONTAINS"
        );
        rule.put("targetField", source.targetField());
        rule.put("operand", value);
      }
      default -> throw new ProfileCatalogException("Unsupported recognition condition kind " + input.kind());
    }
    putOptional(rule, "controlLevel", input.controlLevel());
    putOptional(rule, "controlType", input.controlType());
    return rule;
  }

  private Condition condition(
    ControlRecognitionRule rule,
    String protocol,
    Map<String, Source> sources
  ) {
    return switch (rule.ruleType()) {
      case "SPECIMEN_ID_PREFIX" -> new Condition(
        rule.key(),
        "SPECIMEN_ID_STARTS_WITH",
        null,
        "Specimen ID",
        "Specimen ID starts with " + rule.operand(),
        rule.operand(),
        true,
        rule.controlLevel(),
        rule.controlType()
      );
      case "SPECIMEN_ID_PATTERN" -> new Condition(
        rule.key(),
        "CONFIGURED_SPECIMEN_ID_PATTERN",
        null,
        "Specimen ID",
        "Specimen ID matches a configured pattern",
        null,
        false,
        rule.controlLevel(),
        rule.controlType()
      );
      case "FIELD_EQUALS", "FIELD_CONTAINS" -> {
        String sourceKey = sourceKey(protocol, rule.targetField());
        Source source = sources.get(sourceKey);
        String operator = "FIELD_EQUALS".equals(rule.ruleType()) ? "equals" : "contains";
        yield new Condition(
          rule.key(),
          "FIELD_EQUALS".equals(rule.ruleType()) ? "FIELD_VALUE_EQUALS" : "FIELD_VALUE_CONTAINS",
          sourceKey,
          source.label(),
          source.label() + " " + operator + " " + rule.operand(),
          rule.operand(),
          true,
          rule.controlLevel(),
          rule.controlType()
        );
      }
      default -> throw new ProfileCatalogException(
        "Unsupported control-result recognition rule type " + rule.ruleType()
      );
    };
  }

  private Map<String, Source> sources(JsonNode profile, String protocol) {
    Map<String, Source> sources = new LinkedHashMap<>();
    JsonNode rules = profile == null ? null : profile.path("controlResultRecognition").path("rules");
    if (rules != null && rules.isObject()) {
      rules.elements().forEachRemaining(rule -> {
        String targetField = nullableText(rule.path("targetField").asText(null));
        if (targetField != null) {
          addSource(sources, protocol, targetField);
        }
      });
    }
    if ("FILE".equals(protocol)) {
      JsonNode columnMapping = profile.path("column_mapping");
      if (columnMapping.isObject()) {
        columnMapping.elements().forEachRemaining(value -> {
          String targetField = fileTarget(value.asText(null));
          if (targetField != null) {
            addSource(sources, protocol, targetField);
          }
        });
      }
    }
    return sources;
  }

  private void addSource(Map<String, Source> sources, String protocol, String targetField) {
    String key = sourceKey(protocol, targetField);
    sources.putIfAbsent(
      key,
      new Source(key, ControlRecognitionFieldLabels.label(protocol, targetField), targetField)
    );
  }

  private String sourceKey(String protocol, String targetField) {
    ObjectNode identity = objectMapper.createObjectNode();
    identity.put("protocol", protocol);
    identity.put("targetField", targetField);
    String fingerprint = fingerprints.canonicalFingerprint(identity);
    return "source-" + fingerprint.substring("sha256:".length(), "sha256:".length() + 16);
  }

  private static Map<String, JsonNode> existingRules(JsonNode profile) {
    Map<String, JsonNode> rules = new LinkedHashMap<>();
    JsonNode current = profile.path("controlResultRecognition").path("rules");
    if (current.isObject()) {
      current.fields().forEachRemaining(entry -> rules.put(entry.getKey(), entry.getValue()));
    }
    return rules;
  }

  private static String protocol(JsonNode profile) {
    return profile == null ? null : nullableText(profile.path("protocol").path("name").asText(null));
  }

  private static String fileTarget(String value) {
    if (value == null) {
      return null;
    }
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isUpperCase(character) && result.length() > 0) {
        result.append('_');
      }
      result.append(Character.toUpperCase(character));
    }
    String target = result.toString();
    return ControlRecognitionFieldLabels.isFileField(target) ? target : null;
  }

  private static String requireText(String value, String label) {
    String normalized = nullableText(value);
    if (normalized == null) {
      throw new ProfileCatalogException(label + " is required");
    }
    return normalized;
  }

  private static String nullableText(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static void putOptional(ObjectNode node, String field, String value) {
    String normalized = nullableText(value);
    if (normalized != null) {
      node.put(field, normalized);
    }
  }

  public record View(
    String mode,
    boolean affirmedNoControlResults,
    String description,
    List<Condition> conditions,
    List<Source> availableSources
  ) {
    public View {
      conditions = conditions == null ? List.of() : List.copyOf(conditions);
      availableSources = availableSources == null ? List.of() : List.copyOf(availableSources);
    }
  }

  public record DraftView(
    String draftId,
    String kind,
    String baseProfileId,
    Integer baseRevision,
    String displayName,
    String updatedBy,
    String updatedAt,
    List<String> validationIssues,
    View recognition
  ) {
    public DraftView {
      validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
    }
  }

  public record Source(String key, String label, @JsonIgnore String targetField) {}

  public record Condition(
    String key,
    String kind,
    String sourceKey,
    String sourceLabel,
    String description,
    String value,
    boolean editable,
    String controlLevel,
    String controlType
  ) {}

  public record Update(String mode, boolean affirmedNoControlResults, List<ConditionInput> conditions) {
    public Update {
      conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
  }

  public record ConditionInput(
    String key,
    String kind,
    String sourceKey,
    String value,
    String controlLevel,
    String controlType
  ) {}
}
