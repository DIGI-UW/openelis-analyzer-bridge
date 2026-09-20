package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.context.FhirContext;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerContext;
import org.itech.ahb.fhir.FhirBundleBuilder.DeviceInfo;
import org.junit.jupiter.api.Test;

class HL7RecognitionEvidenceTest {

  private static final String DELIVERY_ID = "hl7-v1:" + "b".repeat(64);

  @Test
  void normalizedObservationsRetainTheirOwnFieldEvidence() {
    var recognition = TestControlRecognitions.rule("FIELD_EQUALS", "OBX.3.2", "CONTROL");
    var parsed = HL7ResultParser.parse(
      List.of(
        "OBR|1||ACC001|PANEL",
        "OBX|1|NM|FIRST^PATIENT||1|unit",
        "OBX|2|NM|SECOND^CONTROL||2|unit",
        "OBX|3|NM|THIRD||3|unit"
      ),
      recognition
    );
    assertNotNull(parsed);
    var context = new AnalyzerContext(
      "test-connection",
      "test-analyzer",
      "test-profile",
      1,
      "HL7",
      "TCP",
      DeviceInfo.fromSenderToken("127.0.0.1", "TEST"),
      recognition,
      "sha256:" + "0".repeat(64)
    );
    String json = FhirBundleBuilder.buildNormalizedBundle(
      parsed.accessionNumber(),
      parsed.results(),
      context,
      code -> null,
      DELIVERY_ID
    );
    Bundle bundle = FhirContext.forR4().newJsonParser().parseResource(Bundle.class, json);
    var observations = bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Observation.class::isInstance)
      .map(Observation.class::cast)
      .toList();
    assertEquals(3, observations.size());
    String root = "https://openelis-global.org/fhir/StructureDefinition/";
    for (int index = 0; index < observations.size(); index++) {
      Observation observation = observations.get(index);
      assertEquals(
        index == 1 ? "CONTROL" : "PATIENT",
        observation.getExtensionByUrl(root + "analyzer-result-classification").getValue().primitiveValue()
      );
      var evidence = observation
        .getExtensionByUrl(root + "analyzer-control-recognition")
        .getExtensionByUrl("evaluation");
      assertNotNull(evidence);
      assertEquals("OBX.3.2", evidence.getExtensionByUrl("sourceField").getValue().primitiveValue());
      assertEquals(Boolean.toString(index == 1), evidence.getExtensionByUrl("matched").getValue().primitiveValue());
      assertEquals(
        Boolean.toString(index < 2),
        evidence.getExtensionByUrl("sourcePresent").getValue().primitiveValue()
      );
      if (index < 2) {
        assertEquals(
          index == 1 ? "CONTROL" : "PATIENT",
          evidence.getExtensionByUrl("rawValue").getValue().primitiveValue()
        );
      } else {
        assertNull(evidence.getExtensionByUrl("rawValue"), "missing fields must not inherit prior result evidence");
      }
    }
  }
}
