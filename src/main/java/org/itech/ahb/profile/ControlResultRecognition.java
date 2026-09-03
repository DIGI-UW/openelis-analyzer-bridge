package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Runtime form of one pinned profile revision's control-result recognition. */
public record ControlResultRecognition(Mode mode, List<ControlRecognitionRule> rules) {

  public enum Mode {
    RULES,
    NONE
  }

  public ControlResultRecognition {
    Objects.requireNonNull(mode, "mode is required");
    rules = rules == null ? List.of() : List.copyOf(rules);
    if (mode == Mode.RULES && rules.isEmpty()) {
      throw new IllegalArgumentException("RULES recognition requires at least one rule");
    }
    if (mode == Mode.NONE && !rules.isEmpty()) {
      throw new IllegalArgumentException("NONE recognition cannot contain rules");
    }
  }

  public static ControlResultRecognition none() {
    return new ControlResultRecognition(Mode.NONE, List.of());
  }

  public static ControlResultRecognition rules(List<ControlRecognitionRule> rules) {
    return new ControlResultRecognition(Mode.RULES, rules);
  }

  public ControlRecognitionSummary summary() {
    return ControlRecognitionSummary.from(this);
  }

  public static ControlResultRecognition fromProfile(JsonNode recognition) {
    String mode = requiredText(recognition, "mode");
    if ("NONE".equals(mode)) {
      if (!recognition.path("affirmedNoControlResults").asBoolean(false)) {
        throw new IllegalArgumentException(
          "NONE recognition requires affirmedNoControlResults=true"
        );
      }
      return none();
    }
    if (!"RULES".equals(mode)) {
      throw new IllegalArgumentException(
        "Unsupported profile control recognition mode " + mode
      );
    }

    List<ControlRecognitionRule> rules = new ArrayList<>();
    recognition.path("rules").fields().forEachRemaining(entry -> {
      JsonNode rule = entry.getValue();
      rules.add(
        new ControlRecognitionRule(
          entry.getKey(),
          requiredText(rule, "ruleType"),
          nullableText(rule, "targetField"),
          requiredText(rule, "operand"),
          nullableText(rule, "controlLevel"),
          nullableText(rule, "controlType")
        )
      );
    });
    return rules(rules);
  }

  private static String requiredText(JsonNode node, String field) {
    String value = nullableText(node, field);
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      return null;
    }
    return value.asText();
  }
}
