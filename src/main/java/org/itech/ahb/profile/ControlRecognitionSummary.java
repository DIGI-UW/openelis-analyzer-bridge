package org.itech.ahb.profile;

import java.util.ArrayList;
import java.util.List;

/** Human-readable, non-executable view of one profile revision's control recognition. */
public record ControlRecognitionSummary(
  String recognitionFingerprint,
  String mode,
  String description,
  boolean affirmedNoControlResults,
  List<Condition> conditions
) {

  public ControlRecognitionSummary {
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
  }

  public static ControlRecognitionSummary from(ControlResultRecognition recognition) {
    if (recognition.mode() == ControlResultRecognition.Mode.NONE) {
      return new ControlRecognitionSummary(
        null,
        "NONE",
        "This analyzer interface transports no control results.",
        true,
        List.of()
      );
    }
    List<Condition> conditions = new ArrayList<>();
    for (ControlRecognitionRule rule : recognition.rules()) {
      conditions.add(condition(rule));
    }
    return new ControlRecognitionSummary(
      null,
      "RULES",
      "Any listed condition identifies a control result.",
      false,
      conditions
    );
  }

  public ControlRecognitionSummary withFingerprint(String fingerprint) {
    return new ControlRecognitionSummary(
      fingerprint,
      mode,
      description,
      affirmedNoControlResults,
      conditions
    );
  }

  private static Condition condition(ControlRecognitionRule rule) {
    return switch (rule.ruleType()) {
      case "SPECIMEN_ID_PREFIX" -> new Condition(
        rule.key(),
        "SPECIMEN_ID_STARTS_WITH",
        "Specimen ID",
        rule.operand(),
        "Specimen ID starts with " + rule.operand(),
        rule.controlLevel(),
        rule.controlType()
      );
      case "SPECIMEN_ID_PATTERN" -> new Condition(
        rule.key(),
        "CONFIGURED_SPECIMEN_ID_PATTERN",
        "Specimen ID",
        null,
        "Specimen ID matches the configured pattern",
        rule.controlLevel(),
        rule.controlType()
      );
      case "FIELD_EQUALS" -> {
        String sourceLabel = ControlRecognitionFieldLabels.label(rule.targetField());
        yield new Condition(
          rule.key(),
          "FIELD_VALUE_EQUALS",
          sourceLabel,
          rule.operand(),
          sourceLabel + " equals " + rule.operand(),
          rule.controlLevel(),
          rule.controlType()
        );
      }
      case "FIELD_CONTAINS" -> {
        String sourceLabel = ControlRecognitionFieldLabels.label(rule.targetField());
        yield new Condition(
          rule.key(),
          "FIELD_VALUE_CONTAINS",
          sourceLabel,
          rule.operand(),
          sourceLabel + " contains " + rule.operand(),
          rule.controlLevel(),
          rule.controlType()
        );
      }
      default -> throw new IllegalArgumentException(
        "Unsupported control recognition rule type " + rule.ruleType()
      );
    };
  }

  public record Condition(
    String key,
    String kind,
    String sourceLabel,
    String value,
    String description,
    String controlLevel,
    String controlType
  ) {}
}
