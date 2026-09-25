package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthetic messages with documented OBX/NTE/SPM grouping; not physical instrument acceptance. */
class HL7FollowingSpecimenTest {

  private static final ControlResultRecognition RECOGNITION = TestControlRecognitions.rule(
    "FIELD_EQUALS",
    "SPM.11",
    "Q"
  );

  private static String specimen(String role) {
    return "SPM|1||||||||||" + role;
  }

  private static HL7ResultParser.ParsedResults following(List<String> segments, ControlResultRecognition rules) {
    return HL7ResultParser.parse(segments, rules, org.itech.ahb.profile.Hl7SpecimenPosition.FOLLOWING_OBX);
  }

  @Test
  void followingSpecimenIsBoundToItsObservationAndMissingFieldsStayMissing() {
    var parsed = following(
      List.of(
        "OBR|1||ACC|PANEL",
        "OBX|1|NM|A||1|unit",
        "NTE|1||note",
        specimen("Q"),
        "OBX|2|NM|B||2|unit",
        specimen("P"),
        "OBX|3|NM|C||3|unit",
        specimen("Q"),
        "OBX|4|NM|D||4|unit",
        "OBX|5|NM|E||5|unit",
        "SPM|1"
      ),
      RECOGNITION
    );
    assertNotNull(parsed);
    assertEquals("ACC", parsed.accessionNumber());
    assertEquals(List.of(true, false, true, false, false), parsed.results().stream().map(r -> r.isControl()).toList());
    assertEquals(List.of("1", "2", "3", "4", "5"), parsed.results().stream().map(r -> r.value()).toList());
    for (int i = 0; i < 5; i++) {
      var evidence = parsed.results().get(i).controlRecognitionAssessment().evaluations().get(0);
      assertEquals("SPM.11", evidence.sourceField());
      assertEquals(i == 0 || i == 2, evidence.matched());
      assertEquals(i == 0 || i == 2 ? "Q" : i == 1 ? "P" : "", evidence.rawValue());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "ORC|NW|AFTER", "OBR|2||ACC|AFTER", "PID|2||AFTER", "MSH|^~\\&|AFTER|LAB" })
  void aLaterBoundaryCannotRewritePendingEvidence(String boundary) {
    String target =
      boundary.substring(0, 3) +
      switch (boundary.substring(0, 3)) {
        case "ORC" -> ".2";
        case "OBR" -> ".4";
        default -> ".3";
      };
    var rules = TestControlRecognitions.rule("FIELD_EQUALS", target, "BEFORE");
    var parsed = following(
      List.of(
        "MSH|^~\\&|BEFORE|LAB",
        "PID|1||BEFORE",
        "ORC|NW|BEFORE",
        "OBR|1||ACC|BEFORE",
        "OBX|1|NM|A||1|unit",
        specimen("P"),
        boundary
      ),
      rules
    );
    assertNotNull(parsed);
    assertEquals(1, parsed.results().size());
    assertTrue(parsed.results().get(0).isControl());
    assertEquals("BEFORE", parsed.results().get(0).controlRecognitionAssessment().evaluations().get(0).rawValue());
  }

  @ParameterizedTest
  @ValueSource(strings = { "ORC|NW|AFTER", "OBR|2||ACC|AFTER", "PID|2||AFTER", "MSH|^~\\&|AFTER|LAB" })
  void aSpecimenAfterAnotherOrderOrPatientCannotClassifyThePendingObservation(String boundary) {
    var parsed = following(List.of("OBR|1||ACC|PANEL", "OBX|1|NM|A||1|unit", boundary, specimen("Q")), RECOGNITION);
    assertNotNull(parsed);
    assertEquals(1, parsed.results().size());
    assertFalse(parsed.results().get(0).isControl());
    assertEquals("", parsed.results().get(0).controlRecognitionAssessment().evaluations().get(0).rawValue());
  }

  @Test
  void shorterAndInvalidObservationsDoNotInheritSpecimenOrObservationEvidence() {
    var parsed = following(
      List.of(
        "OBR|1||ACC|PANEL",
        "OBX|1|NM|A^CONTROL||1|unit",
        specimen("Q"),
        "OBX|2",
        specimen("Q"),
        "OBX|3|NM|B||2|unit",
        specimen("P")
      ),
      TestControlRecognitions.rule("FIELD_EQUALS", "OBX.3.2", "CONTROL")
    );
    assertNotNull(parsed);
    assertEquals(List.of(true, false), parsed.results().stream().map(r -> r.isControl()).toList());
    assertEquals("", parsed.results().get(1).controlRecognitionAssessment().evaluations().get(0).rawValue());
  }

  @Test
  void defaultPreservesPrecedingSpecimenBehavior() {
    var segments = List.of("OBR|1||ACC|PANEL", specimen("Q"), "OBX|1|NM|A||1|unit");
    var prior = HL7ResultParser.parse(segments, RECOGNITION);
    var explicit = HL7ResultParser.parse(segments, RECOGNITION, org.itech.ahb.profile.Hl7SpecimenPosition.PRECEDING);
    assertNotNull(prior);
    assertTrue(prior.results().get(0).isControl());
    assertEquals(prior, explicit);
  }

  @Test
  void followsDeclaredDelimiters() {
    var parsed = following(
      List.of("MSH#$!?@#SENDER#LAB", "OBR#1##ACC#PANEL", "OBX#1#NM#A##1#unit", specimen("Q").replace('|', '#')),
      RECOGNITION
    );
    assertNotNull(parsed);
    assertTrue(parsed.results().get(0).isControl());
    assertEquals("Q", parsed.results().get(0).controlRecognitionAssessment().evaluations().get(0).rawValue());
  }
}
