package org.itech.ahb.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;

/** Evaluates the explicit recognition mode and named rules from a profile. */
@Slf4j
public final class ControlResultRecognitionEvaluator {

  private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

  private ControlResultRecognitionEvaluator() {}

  public static Assessment evaluate(
    ControlResultRecognition recognition,
    String specimenId,
    Map<String, String> fieldValues
  ) {
    Objects.requireNonNull(recognition, "recognition is required");
    if (recognition.mode() == ControlResultRecognition.Mode.NONE) {
      return new Assessment(
        recognition.mode(),
        Outcome.NOT_EVALUATED,
        List.of(),
        null
      );
    }
    List<RuleEvaluation> evaluations = new ArrayList<>();
    ControlRecognitionRule matchedRule = null;
    for (ControlRecognitionRule rule : recognition.rules()) {
      boolean matched = matches(rule, specimenId, fieldValues);
      String sourceField = sourceField(rule);
      String rawValue = sourceValue(rule, specimenId, fieldValues);
      evaluations.add(new RuleEvaluation(rule, sourceField, rawValue, matched));
      if (matched && matchedRule == null) {
        matchedRule = rule;
        log.debug(
          "Control recognition rule matched: key={}, type={}, field={}",
          rule.key(),
          rule.ruleType(),
          rule.targetField()
        );
      }
    }
    return new Assessment(
      recognition.mode(),
      matchedRule == null ? Outcome.NO_MATCH : Outcome.MATCH,
      evaluations,
      matchedRule
    );
  }

  private static String sourceField(ControlRecognitionRule rule) {
    return switch (rule.ruleType()) {
      case "FIELD_EQUALS", "FIELD_CONTAINS" -> rule.targetField();
      case "SPECIMEN_ID_PREFIX", "SPECIMEN_ID_PATTERN" -> "specimenId";
      default -> throw new IllegalStateException(
        "Unsupported control recognition rule type " + rule.ruleType()
      );
    };
  }

  private static String sourceValue(
    ControlRecognitionRule rule,
    String specimenId,
    Map<String, String> fieldValues
  ) {
    String value = switch (rule.ruleType()) {
      case "FIELD_EQUALS", "FIELD_CONTAINS" ->
        fieldValues == null ? null : fieldValues.get(rule.targetField());
      case "SPECIMEN_ID_PREFIX", "SPECIMEN_ID_PATTERN" -> specimenId;
      default -> throw new IllegalStateException(
        "Unsupported control recognition rule type " + rule.ruleType()
      );
    };
    return value == null ? "" : value;
  }

  private static boolean matches(
    ControlRecognitionRule rule,
    String specimenId,
    Map<String, String> fieldValues
  ) {
    return switch (rule.ruleType()) {
      case "FIELD_EQUALS" -> {
        String value = fieldValues == null ? null : fieldValues.get(rule.targetField());
        yield value != null && value.trim().equalsIgnoreCase(rule.operand().trim());
      }
      case "FIELD_CONTAINS" -> {
        String value = fieldValues == null ? null : fieldValues.get(rule.targetField());
        yield value != null &&
        value
          .toUpperCase(Locale.ROOT)
          .contains(rule.operand().toUpperCase(Locale.ROOT));
      }
      case "SPECIMEN_ID_PREFIX" ->
        specimenId != null &&
        specimenId
          .toUpperCase(Locale.ROOT)
          .startsWith(rule.operand().toUpperCase(Locale.ROOT));
      case "SPECIMEN_ID_PATTERN" -> matchesPattern(rule, specimenId);
      default -> throw new IllegalStateException(
        "Unsupported control recognition rule type " + rule.ruleType()
      );
    };
  }

  private static boolean matchesPattern(ControlRecognitionRule rule, String specimenId) {
    if (specimenId == null) {
      return false;
    }
    try {
      Pattern pattern = PATTERN_CACHE.computeIfAbsent(
        rule.operand(),
        value -> Pattern.compile(value, Pattern.CASE_INSENSITIVE)
      );
      return pattern.matcher(specimenId).matches();
    } catch (PatternSyntaxException exception) {
      log.warn(
        "Invalid control recognition regex for rule '{}': {}",
        rule.key(),
        exception.getMessage()
      );
      return false;
    }
  }

  public enum Outcome {
    MATCH,
    NO_MATCH,
    NOT_EVALUATED
  }

  public record RuleEvaluation(
    ControlRecognitionRule rule,
    String sourceField,
    String rawValue,
    boolean matched
  ) {
    public RuleEvaluation {
      Objects.requireNonNull(rule, "rule is required");
      Objects.requireNonNull(sourceField, "source field is required");
      Objects.requireNonNull(rawValue, "raw value is required");
    }
  }

  public record Assessment(
    ControlResultRecognition.Mode mode,
    Outcome outcome,
    List<RuleEvaluation> evaluations,
    ControlRecognitionRule matched
  ) {
    public Assessment {
      Objects.requireNonNull(mode, "mode is required");
      Objects.requireNonNull(outcome, "outcome is required");
      evaluations = evaluations == null ? List.of() : List.copyOf(evaluations);
    }

    public Optional<ControlRecognitionRule> matchedRule() {
      return Optional.ofNullable(matched);
    }
  }
}
