package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ControlResultRecognitionTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void explicitNoneNeverRecognizesAControl() {
    ControlResultRecognition recognition = ControlResultRecognition.none();

    var assessment = ControlResultRecognitionEvaluator.evaluate(
      recognition,
      "CNEG-2026",
      Map.of("O.12", "Q", "QC_TASK", "CONTROL")
    );

    assertThat(assessment.outcome())
      .isEqualTo(ControlResultRecognitionEvaluator.Outcome.NOT_EVALUATED);
    assertThat(assessment.evaluations()).isEmpty();
    assertThat(assessment.matchedRule()).isEmpty();
  }

  @Test
  void missingRecognitionIsRejectedInsteadOfBehavingAsNone() {
    assertThatThrownBy(() ->
      ControlResultRecognitionEvaluator.evaluate(null, "QC-2026", Map.of())
    )
      .isInstanceOf(NullPointerException.class)
      .hasMessage("recognition is required");
  }

  @Test
  void unsupportedMatchersCannotEnterTheRuntimeModel() {
    assertThatThrownBy(() ->
      new ControlRecognitionRule("unknown", "VENDOR_GUESS", null, "Q", null, null)
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Unsupported control recognition rule type VENDOR_GUESS");
  }

  @Test
  void fieldMatchersRequireTheirProtocolField() {
    assertThatThrownBy(() ->
      new ControlRecognitionRule("missing-field", "FIELD_EQUALS", null, "Q", null, null)
    )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("targetField is required for FIELD_EQUALS");
  }

  @Test
  void rulesUseOrSemanticsAndRetainTheMatchedRuleMetadata() {
    ControlRecognitionRule fieldRule = new ControlRecognitionRule(
      "field-does-not-match",
      "FIELD_EQUALS",
      "O.12",
      "P",
      null,
      null
    );
    ControlRecognitionRule prefixRule = new ControlRecognitionRule(
      "normal-control-prefix",
      "SPECIMEN_ID_PREFIX",
      null,
      "QC-",
      "NORMAL",
      "ASSAY_CONTROL"
    );
    ControlResultRecognition recognition = ControlResultRecognition.rules(
      List.of(fieldRule, prefixRule)
    );

    var assessment = ControlResultRecognitionEvaluator.evaluate(
      recognition,
      "QC-2026-001",
      Map.of("O.12", "Q")
    );

    assertThat(assessment.outcome())
      .isEqualTo(ControlResultRecognitionEvaluator.Outcome.MATCH);
    assertThat(assessment.matchedRule()).contains(prefixRule);
    assertThat(assessment.evaluations()).hasSize(2);
    assertThat(assessment.evaluations().get(0))
      .extracting(
        ControlResultRecognitionEvaluator.RuleEvaluation::sourceField,
        ControlResultRecognitionEvaluator.RuleEvaluation::rawValue,
        ControlResultRecognitionEvaluator.RuleEvaluation::matched
      )
      .containsExactly("O.12", "Q", false);
    assertThat(assessment.evaluations().get(1))
      .extracting(
        ControlResultRecognitionEvaluator.RuleEvaluation::sourceField,
        ControlResultRecognitionEvaluator.RuleEvaluation::rawValue,
        ControlResultRecognitionEvaluator.RuleEvaluation::matched
      )
      .containsExactly("specimenId", "QC-2026-001", true);
  }

  @Test
  void materializesStableKeysAndMetadataFromProfileJson() throws Exception {
    var recognition = ControlResultRecognition.fromProfile(
      objectMapper.readTree(
        """
        {
          "mode": "RULES",
          "rules": {
            "normal-control": {
              "ruleType": "SPECIMEN_ID_PREFIX",
              "operand": "QC-",
              "controlLevel": "NORMAL",
              "controlType": "ASSAY_CONTROL"
            }
          }
        }
        """
      )
    );

    assertThat(recognition.mode()).isEqualTo(ControlResultRecognition.Mode.RULES);
    assertThat(recognition.rules()).singleElement().satisfies(rule -> {
      assertThat(rule.key()).isEqualTo("normal-control");
      assertThat(rule.controlLevel()).isEqualTo("NORMAL");
      assertThat(rule.controlType()).isEqualTo("ASSAY_CONTROL");
    });
  }

  @Test
  void noneRequiresAnExplicitAffirmation() throws Exception {
    var recognition = objectMapper.readTree(
      """
      {
        "mode": "NONE"
      }
      """
    );

    assertThatThrownBy(() -> ControlResultRecognition.fromProfile(recognition))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("NONE recognition requires affirmedNoControlResults=true");
  }

  @Test
  void rulesExposeStableHumanReadableConditionsWithoutRawPatterns() {
    ControlResultRecognition recognition = ControlResultRecognition.rules(
      List.of(
        new ControlRecognitionRule(
          "control-prefix",
          "SPECIMEN_ID_PREFIX",
          null,
          "QC-",
          "NORMAL",
          "ASSAY_CONTROL"
        ),
        new ControlRecognitionRule(
          "control-pattern",
          "SPECIMEN_ID_PATTERN",
          null,
          "^C(?:NEG|POS)-.*$",
          null,
          null
        )
      )
    );

    ControlRecognitionSummary summary = recognition.summary();

    assertThat(summary.mode()).isEqualTo("RULES");
    assertThat(summary.affirmedNoControlResults()).isFalse();
    assertThat(summary.conditions()).hasSize(2);
    assertThat(summary.conditions().get(0).key()).isEqualTo("control-prefix");
    assertThat(summary.conditions().get(0).kind()).isEqualTo("SPECIMEN_ID_STARTS_WITH");
    assertThat(summary.conditions().get(0).sourceLabel()).isEqualTo("Specimen ID");
    assertThat(summary.conditions().get(0).value()).isEqualTo("QC-");
    assertThat(summary.conditions().get(0).description()).isEqualTo("Specimen ID starts with QC-");
    assertThat(summary.conditions().get(0).controlLevel()).isEqualTo("NORMAL");
    assertThat(summary.conditions().get(1).description())
      .isEqualTo("Specimen ID matches the configured pattern")
      .doesNotContain("^C(?:NEG|POS)-.*$");
    assertThat(summary.conditions().get(1).kind()).isEqualTo("CONFIGURED_SPECIMEN_ID_PATTERN");
    assertThat(summary.conditions().get(1).sourceLabel()).isEqualTo("Specimen ID");
    assertThat(summary.conditions().get(1).value()).isNull();
  }

  @Test
  void fieldRulesUseProtocolLanguageWithoutExposingRawMatcherFields() {
    ControlRecognitionSummary summary = ControlResultRecognition.rules(
      List.of(
        new ControlRecognitionRule("astm-control", "FIELD_EQUALS", "O.12", "Q", null, null),
        new ControlRecognitionRule("hl7-control", "FIELD_CONTAINS", "OBR.16", "CONTROL", null, null),
        new ControlRecognitionRule("file-control", "FIELD_EQUALS", "QC_TASK", "Positive", null, null)
      )
    ).summary();

    assertThat(summary.conditions())
      .extracting(ControlRecognitionSummary.Condition::description)
      .containsExactly(
        "Order field 12 equals Q",
        "Observation request field 16 contains CONTROL",
        "Control task equals Positive"
      );
    assertThat(summary.conditions())
      .extracting(ControlRecognitionSummary.Condition::kind)
      .containsExactly("FIELD_VALUE_EQUALS", "FIELD_VALUE_CONTAINS", "FIELD_VALUE_EQUALS");
    assertThat(summary.conditions())
      .extracting(ControlRecognitionSummary.Condition::sourceLabel)
      .containsExactly("Order field 12", "Observation request field 16", "Control task");
    assertThat(summary.conditions())
      .extracting(ControlRecognitionSummary.Condition::value)
      .containsExactly("Q", "CONTROL", "Positive");
    assertThat(summary.conditions().toString())
      .doesNotContain("O.12")
      .doesNotContain("OBR.16")
      .doesNotContain("QC_TASK");
  }

  @Test
  void noneSummaryStatesThatTheInterfaceTransportsNoControlResults() {
    ControlRecognitionSummary summary = ControlResultRecognition.none().summary();

    assertThat(summary.mode()).isEqualTo("NONE");
    assertThat(summary.affirmedNoControlResults()).isTrue();
    assertThat(summary.description())
      .isEqualTo("This analyzer interface transports no control results.");
    assertThat(summary.conditions()).isEmpty();
  }
}
