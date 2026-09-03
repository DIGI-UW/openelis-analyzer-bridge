package org.itech.ahb.profile;

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

  public static Optional<ControlRecognitionRule> findMatchingRule(
    ControlResultRecognition recognition,
    String specimenId,
    Map<String, String> fieldValues
  ) {
    Objects.requireNonNull(recognition, "recognition is required");
    if (recognition.mode() == ControlResultRecognition.Mode.NONE) {
      return Optional.empty();
    }
    for (ControlRecognitionRule rule : recognition.rules()) {
      if (matches(rule, specimenId, fieldValues)) {
        log.debug(
          "Control recognition rule matched: key={}, type={}, field={}",
          rule.key(),
          rule.ruleType(),
          rule.targetField()
        );
        return Optional.of(rule);
      }
    }
    return Optional.empty();
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
}
