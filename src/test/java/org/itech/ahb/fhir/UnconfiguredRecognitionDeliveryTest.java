package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.context.FhirContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ControlResultRecognitionEvaluator.Outcome;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Empty explicit recognition must reach normalized delivery without fabricated control evidence. */
class UnconfiguredRecognitionDeliveryTest {

  @ParameterizedTest
  @ValueSource(strings = { "ASTM", "HL7", "FILE" })
  void unconfiguredRecognitionSurvivesParsingAndNormalizedDelivery(String protocol) {
    var recognition = ControlResultRecognition.rules(List.of());
    var parsed =
      switch (protocol) {
        case "ASTM" -> ASTMResultParser.parseRaw(
          "H|\\^&|||TEST\rO|1|QC-UNCONFIGURED\rR|1|^^^WBC|7.5|10*3/uL\rL|1|N\r",
          recognition,
          AstmResultRecordSelection.all()
        );
        case "HL7" -> HL7ResultParser.parseRaw(
          "MSH|^~\\&|TEST|LAB|OE|LAB|20260925||ORU^R01|1|P|2.3.1\rOBR|1||QC-UNCONFIGURED\rOBX|1|NM|WBC||7.5|10*3/uL\r",
          recognition
        );
        default -> FileResultParser.parseCsv(
          "Sample,Result,Unit\nQC-UNCONFIGURED,7.5,10*3/uL\n".getBytes(StandardCharsets.UTF_8),
          Map.of("Sample", "sampleId", "Result", "result", "Unit", "units"),
          ",",
          0,
          "WBC",
          recognition
        ).get(0);
      };
    assertEquals("QC-UNCONFIGURED", parsed.accessionNumber());
    assertEquals(1, parsed.results().size());
    var result = parsed.results().get(0);
    assertFalse(result.isControl());
    assertEquals(Outcome.NOT_EVALUATED, result.controlRecognitionAssessment().outcome());
    assertTrue(result.controlRecognitionAssessment().evaluations().isEmpty());
    var context = new FhirBundleBuilder.AnalyzerContext(
      "connection",
      "analyzer",
      "profile",
      1,
      protocol,
      "FILE".equals(protocol) ? "FILE" : "TCP",
      FhirBundleBuilder.DeviceInfo.fromSenderToken("127.0.0.1", "TEST"),
      recognition,
      "sha256:" + "a".repeat(64)
    );
    String json = FhirBundleBuilder.buildNormalizedBundle(
      parsed.accessionNumber(),
      parsed.results(),
      context,
      code -> null,
      "test:" + "b".repeat(64)
    );
    var bundle = FhirContext.forR4Cached().newJsonParser().parseResource(Bundle.class, json);
    var observations = bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Observation.class::isInstance)
      .map(Observation.class::cast)
      .toList();
    assertEquals(1, observations.size());
    var observation = observations.get(0);
    String root = "https://openelis-global.org/fhir/StructureDefinition/";
    assertEquals(
      "PATIENT",
      observation.getExtensionByUrl(root + "analyzer-result-classification").getValue().primitiveValue()
    );
    var evidence = observation.getExtensionByUrl(root + "analyzer-control-recognition");
    assertEquals("RULES", evidence.getExtensionByUrl("mode").getValue().primitiveValue());
    assertEquals("NOT_EVALUATED", evidence.getExtensionByUrl("outcome").getValue().primitiveValue());
    assertTrue(evidence.getExtensionsByUrl("evaluation").isEmpty());
  }
}
