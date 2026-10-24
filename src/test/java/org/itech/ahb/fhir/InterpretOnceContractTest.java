package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ResultReading;
import org.junit.jupiter.api.Test;

class InterpretOnceContractTest {

  private static final String ROOT = "https://openelis-global.org/fhir/StructureDefinition/";

  private ResultReading reading() throws Exception {
    return ResultReading.fromProfile(
      new ObjectMapper()
        .readTree(
          """
          {"protocol":{"name":"ASTM"},"default_test_mappings":[
            {"test_code":"ASSAY","values":["DETECTED"],"translations":{"DETECTED":["DETECTE"]},"value_codes":{"DETECTED":[{"system":"http://loinc.org","code":"LA11882-0"}]},
             "components":[{"sub_identity":"PART","values":["NEGATIVE"],"translations":{"NEGATIVE":["NEGATIF"]}}]}]}
          """
        )
    );
  }

  private Observation observation(FhirBundleBuilder.AnalyzerResult result) throws Exception {
    var context = new FhirBundleBuilder.AnalyzerContext(
      "connection",
      "analyzer",
      "site.test",
      1,
      "ASTM",
      "TCP",
      FhirBundleBuilder.DeviceInfo.fromSenderToken("host", "device"),
      ControlResultRecognition.none(),
      "sha256:" + "a".repeat(64),
      null,
      reading()
    );
    String json = FhirBundleBuilder.buildNormalizedBundle("sample", List.of(result), context, code -> null, "message");
    Bundle bundle = FhirContext.forR4().newJsonParser().parseResource(Bundle.class, json);
    return bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Observation.class::isInstance)
      .map(Observation.class::cast)
      .findFirst()
      .orElseThrow();
  }

  @Test
  void translatesCallAndPreservesRawInstrumentText() throws Exception {
    Observation result = observation(FhirBundleBuilder.AnalyzerResult.text("ASSAY", "Assay", "DETECTE"));
    assertEquals("DETECTED", result.getValueCodeableConcept().getText());
    assertEquals("LA11882-0", result.getValueCodeableConcept().getCodingFirstRep().getCode());
    assertEquals("DETECTE", result.getExtensionByUrl(ROOT + "analyzer-raw-value").getValue().primitiveValue());
    assertEquals("DETECTED", result.getExtensionByUrl(ROOT + "analyzer-canonical-answer").getValue().primitiveValue());
  }

  @Test
  void translatedCallBesideNumberIsNotAnInformationalFlag() throws Exception {
    RecordParts parts = new RecordParts(
      "",
      "DETECTE",
      "1.23",
      null,
      null,
      null,
      List.of("SAMPLE WARNING"),
      null,
      null,
      null,
      null,
      null,
      false,
      List.of()
    );
    Observation result = observation(
      FhirBundleBuilder.AnalyzerResult.text("ASSAY", "Assay", "DETECTE").withParts(parts)
    );
    assertEquals("1.23", result.getValueQuantity().getValue().toPlainString());
    assertEquals(
      "call",
      result.getInterpretationFirstRep().getExtensionByUrl(ROOT + "analyzer-result-part").getValue().primitiveValue()
    );
    assertEquals("DETECTED", result.getInterpretationFirstRep().getText());
    assertTrue(
      result.getInterpretationFirstRep().getCoding().stream().anyMatch(code -> "LA11882-0".equals(code.getCode()))
    );
    assertEquals("SAMPLE WARNING", result.getInterpretation().get(1).getText());
  }

  @Test
  void componentTranslationsDoNotBorrowThePrimaryMeaning() throws Exception {
    assertEquals("NEGATIVE", reading().canonicalAnswer("ASSAY", "PART", "NEGATIF"));
    assertEquals("NEGATIF", reading().canonicalAnswer("ASSAY", "", "NEGATIF"));
    assertEquals("UNKNOWN", reading().canonicalAnswer("ASSAY", "", "UNKNOWN"));
  }
}
