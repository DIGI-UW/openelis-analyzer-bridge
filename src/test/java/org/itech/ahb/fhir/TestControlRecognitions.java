package org.itech.ahb.fhir;

import java.util.List;
import org.itech.ahb.profile.ControlRecognitionRule;
import org.itech.ahb.profile.ControlResultRecognition;

final class TestControlRecognitions {

  private TestControlRecognitions() {}

  static ControlResultRecognition rule(
    String ruleType,
    String targetField,
    String operand
  ) {
    return rule("test-rule", ruleType, targetField, operand, null, null);
  }

  static ControlResultRecognition rule(
    String key,
    String ruleType,
    String targetField,
    String operand,
    String controlLevel,
    String controlType
  ) {
    return ControlResultRecognition.rules(
      List.of(
        new ControlRecognitionRule(
          key,
          ruleType,
          targetField,
          operand,
          controlLevel,
          controlType
        )
      )
    );
  }
}
