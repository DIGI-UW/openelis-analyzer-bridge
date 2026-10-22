package org.itech.ahb.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.fhir.HL7ResultParser.ParsedResults;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FILE transport uses the same normalized contract as listener traffic. Raw
 * analyzer identity remains authoritative and LOINC is only an optional hint.
 */
@DisplayName("FileMessageHandler normalized FILE traffic")
class FileMessageHandlerLoincTest {

  private static final FhirContext CTX = FhirContext.forR4();

  private Observation firstObservation(String json) {
    Bundle b = CTX.newJsonParser().parseResource(Bundle.class, json);
    return b
      .getEntry()
      .stream()
      .map(org.hl7.fhir.r4.model.Bundle.BundleEntryComponent::getResource)
      .filter(r -> r instanceof Observation)
      .map(r -> (Observation) r)
      .findFirst()
      .orElseThrow();
  }

  @Test
  void tabularValuesUseTheCapturedDecimalAndFailureRules() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var profile = mapper.readTree(
      """
      {"configDefaults":{"numberFormat":","},"default_test_mappings":[
        {"test_code":"COUNT","run_failure_values":["ERROR"],
         "translations":{"ERROR":["ECHEC"]}}]}
      """
    );
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("43");
    entry.setName("File instrument");
    entry.setBridgeConnectionId("file-connection");
    entry.setProfileId("site.file");
    entry.setProfileRevision(1);
    entry.setProfileFingerprint("sha256:" + "1".repeat(64));
    entry.setControlResultRecognition(ControlResultRecognition.none());
    entry.setRecognitionFingerprint("sha256:" + "0".repeat(64));
    entry.setResultReading(org.itech.ahb.profile.ResultReading.fromProfile(profile));
    var captured = FileReceiptContext.capture(java.nio.file.Path.of("results.csv"), entry, "COUNT");
    var retained = mapper.readValue(mapper.writeValueAsString(captured), FileReceiptContext.class);
    assertEquals(',', retained.decimalSeparator());
    assertTrue(retained.runFailures().get("COUNT").get("").contains("ECHEC"));

    var number = firstObservation(
      FileResultRenderer.buildFileFhirBundle(
        entry,
        new ParsedResults("ACC", List.of(AnalyzerResult.text("COUNT", "Count", "1,23"))),
        "results.csv",
        "1".repeat(64)
      )
    );
    assertEquals(new java.math.BigDecimal("1.23"), number.getValueQuantity().getValue());
    assertEquals(
      "1,23",
      number
        .getExtensionByUrl("https://openelis-global.org/fhir/StructureDefinition/analyzer-raw-value")
        .getValue()
        .primitiveValue()
    );
    var failure = firstObservation(
      FileResultRenderer.buildFileFhirBundle(
        entry,
        new ParsedResults("ACC", List.of(AnalyzerResult.text("COUNT", "Count", "ECHEC"))),
        "results.csv",
        "2".repeat(64)
      )
    );
    assertTrue(failure.hasDataAbsentReason());
    org.junit.jupiter.api.Assertions.assertFalse(failure.hasValue());
  }

  @Test
  @DisplayName("FILE traffic carries exact connection context, raw code, and optional LOINC")
  void fileInboundUsesNormalizedContract() {
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("43");
    entry.setName("QuantStudio 5");
    entry.setBridgeConnectionId("bridge-file-7f3c");
    entry.setProfileId("site.mock-file");
    entry.setProfileRevision(2);
    entry.setControlResultRecognition(ControlResultRecognition.none());
    entry.setRecognitionFingerprint("sha256:" + "0".repeat(64));
    entry.setCodeToLoinc(Map.of("DENV", "7855-0"));
    ParsedResults parsed = new ParsedResults(
      "DEV01260000000000008",
      List.of(AnalyzerResult.numeric("DENV", "DENV", "5000", ""))
    );

    String json = FileMessageHandler.buildFileFhirBundle(entry, parsed, "result-001.csv", "1".repeat(64));
    Bundle bundle = CTX.newJsonParser().parseResource(Bundle.class, json);
    Observation observation = firstObservation(json);
    Device device = bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Device.class::isInstance)
      .map(Device.class::cast)
      .findFirst()
      .orElseThrow();

    assertEquals(
      "bridge-file-7f3c",
      device
        .getIdentifier()
        .stream()
        .filter(value -> "https://openelis-global.org/fhir/analyzer-connection-id".equals(value.getSystem()))
        .findFirst()
        .orElseThrow()
        .getValue()
    );
    assertTrue(
      observation
        .getCode()
        .getCoding()
        .stream()
        .anyMatch(
          value ->
            "https://openelis-global.org/fhir/CodeSystem/analyzer-raw-code".equals(value.getSystem()) &&
            "DENV".equals(value.getCode())
        )
    );
    assertTrue(
      observation
        .getCode()
        .getCoding()
        .stream()
        .anyMatch(value -> "http://loinc.org".equals(value.getSystem()) && "7855-0".equals(value.getCode()))
    );
    // A results file reports no status of its own.
    assertEquals(Observation.ObservationStatus.UNKNOWN, observation.getStatus());
  }

  @Test
  @DisplayName("FILE bundle construction rejects an analyzer without a pinned profile registration")
  void nullEntryIsRejected() {
    ParsedResults parsed = new ParsedResults("ACC", List.of(AnalyzerResult.numeric("DENV", "DENV", "5000", "")));

    NullPointerException error = assertThrows(
      NullPointerException.class,
      () -> FileMessageHandler.buildFileFhirBundle(null, parsed, "43", "1".repeat(64))
    );

    assertEquals("analyzerEntry is required", error.getMessage());
  }

  @Test
  @DisplayName("watched FILE uses the pinned profile code while an explicit upload choice wins")
  void resolvesFileWideTestCodeWithoutGuessing() {
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setFileTestCode("VIH-1");

    assertEquals("VIH-1", FileMessageHandler.resolveFileTestCode(entry, null));
    assertEquals("VIH-1", FileMessageHandler.resolveFileTestCode(entry, "  "));
    assertEquals("CHIKV", FileMessageHandler.resolveFileTestCode(entry, " CHIKV "));
  }
}
