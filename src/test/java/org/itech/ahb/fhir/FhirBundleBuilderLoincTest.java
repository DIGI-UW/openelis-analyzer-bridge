package org.itech.ahb.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerContext;
import org.itech.ahb.fhir.FhirBundleBuilder.AnalyzerResult;
import org.itech.ahb.fhir.FhirBundleBuilder.DeviceInfo;
import org.itech.ahb.profile.ControlRecognitionRule;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ControlResultRecognitionEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FhirBundleBuilder normalized analyzer identity and coding")
class FhirBundleBuilderLoincTest {

  private static final FhirContext FHIR = FhirContext.forR4();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CONNECTION_ID_SYSTEM =
    "https://openelis-global.org/fhir/analyzer-connection-id";
  private static final String ANALYZER_ID_SYSTEM = "https://openelis-global.org/fhir/analyzer-id";
  private static final String RAW_CODE_SYSTEM =
    "https://openelis-global.org/fhir/CodeSystem/analyzer-raw-code";

  @Test
  void preservesRawCodeAndCarriesOptionalLoincHintWithExactConnectionContext() {
    AnalyzerContext context = new AnalyzerContext(
      "bridge-connection-7f3c",
      "oe-analyzer-42",
      "site.mock-hematology",
      3,
      "ASTM",
      "TCP",
      DeviceInfo.fromSenderToken("10.20.30.40", "LAB^Hematology^1"),
      ControlResultRecognition.none(),
      "sha256:" + "0".repeat(64)
    );

    String json = FhirBundleBuilder.buildNormalizedBundle(
      "ACC-1",
      List.of(AnalyzerResult.numeric("WBC", "White Blood Cell", "7.5", "10*3/uL")),
      context,
      Map.of("WBC", "6690-2")::get
    );

    Bundle bundle = FHIR.newJsonParser().parseResource(Bundle.class, json);
    Device device = resource(bundle, Device.class);
    Observation observation = resource(bundle, Observation.class);

    assertThat(bundle.getMeta().getProfile())
      .extracting(profile -> profile.getValue())
      .containsExactly("https://openelis-global.org/fhir/StructureDefinition/analyzer-normalized-bundle-v1");
    assertThat(bundle.getIdentifier().getSystem())
      .isEqualTo("https://openelis-global.org/fhir/analyzer-message-id");
    assertThat(bundle.getIdentifier().getValue()).isNotBlank();
    assertThat(identifier(device, CONNECTION_ID_SYSTEM)).isEqualTo("bridge-connection-7f3c");
    assertThat(identifier(device, ANALYZER_ID_SYSTEM)).isEqualTo("oe-analyzer-42");
    assertThat(extensionString(device, "analyzer-profile-id")).isEqualTo("site.mock-hematology");
    assertThat(extensionInteger(device, "analyzer-profile-revision")).isEqualTo(3);
    assertThat(extensionCode(device, "analyzer-source-protocol")).isEqualTo("ASTM");
    assertThat(coding(observation, RAW_CODE_SYSTEM)).isEqualTo("WBC");
    assertThat(coding(observation, "http://loinc.org")).isEqualTo("6690-2");
  }

  @Test
  void unknownCodeStillCarriesItsRawIdentityWithoutInventingLoinc() {
    String json = FhirBundleBuilder.buildNormalizedBundle(
      "ACC-2",
      List.of(AnalyzerResult.text("VENDOR-NEW-42", "New vendor assay", "POS")),
      new AnalyzerContext(
        "bridge-connection-7f3c",
        "oe-analyzer-42",
        "site.mock-hematology",
        3,
        "HL7",
        "MLLP",
        DeviceInfo.fromSenderToken("10.20.30.40", "LAB"),
        ControlResultRecognition.none(),
        "sha256:" + "0".repeat(64)
      ),
      code -> null
    );

    Observation observation = resource(
      FHIR.newJsonParser().parseResource(Bundle.class, json),
      Observation.class
    );
    assertThat(coding(observation, RAW_CODE_SYSTEM)).isEqualTo("VENDOR-NEW-42");
    assertThat(observation.getCode().getCoding())
      .noneMatch(value -> "http://loinc.org".equals(value.getSystem()));
  }

  @Test
  void explicitNoneRecognitionProducesAContractConformantPatientBundle() throws Exception {
    String json = FhirBundleBuilder.buildNormalizedBundle(
      "ACC-3",
      List.of(AnalyzerResult.text("HIV-INTERP", "HIV interpretation", "NEGATIVE")),
      new AnalyzerContext(
        "bridge-connection-7f3c",
        "oe-analyzer-42",
        "site.mock-none",
        1,
        "HL7",
        "MLLP",
        DeviceInfo.fromSenderToken("10.20.30.40", "LAB"),
        ControlResultRecognition.none(),
        "sha256:" + "0".repeat(64)
      ),
      code -> null
    );

    assertConforms(json);
  }

  @Test
  void evaluatedRulesProduceAContractConformantControlBundle() throws Exception {
    ControlResultRecognition recognition = ControlResultRecognition.rules(List.of(
      new ControlRecognitionRule(
        "qc-prefix",
        "SPECIMEN_ID_PREFIX",
        null,
        "QC-",
        "NORMAL",
        "ASSAY_CONTROL"
      )
    ));
    ControlResultRecognitionEvaluator.Assessment assessment =
      ControlResultRecognitionEvaluator.evaluate(recognition, "QC-001", Map.of());
    AnalyzerResult control = AnalyzerResult
      .numeric("WBC", "White Blood Cell", "7.5", "10*3/uL")
      .withControlRecognition(assessment)
      .withControl(true)
      .withControlLevel("NORMAL")
      .withControlType("ASSAY_CONTROL");

    String json = FhirBundleBuilder.buildNormalizedBundle(
      "QC-001",
      List.of(control),
      new AnalyzerContext(
        "bridge-connection-7f3c",
        "oe-analyzer-42",
        "site.mock-rules",
        2,
        "ASTM",
        "TCP",
        DeviceInfo.fromSenderToken("10.20.30.40", "LAB"),
        recognition,
        "sha256:" + "1".repeat(64)
      ),
      Map.of("WBC", "6690-2")::get
    );

    assertConforms(json);
    Observation observation = resource(FHIR.newJsonParser().parseResource(Bundle.class, json), Observation.class);
    assertThat(observation.getMeta().getTag())
      .anyMatch(tag -> "QC".equals(tag.getCode()));
  }

  @Test
  void absentRuleSourceProducesContractConformantPatientEvidence() throws Exception {
    ControlResultRecognition recognition = ControlResultRecognition.rules(List.of(
      new ControlRecognitionRule(
        "order-action-control",
        "FIELD_EQUALS",
        "O.12",
        "Q",
        null,
        null
      )
    ));
    ControlResultRecognitionEvaluator.Assessment assessment =
      ControlResultRecognitionEvaluator.evaluate(recognition, "ACC-4", Map.of());
    AnalyzerResult patient = AnalyzerResult
      .text("MTB-RIF", "Xpert MTB/RIF", "NOT DETECTED")
      .withControlRecognition(assessment);

    String json = FhirBundleBuilder.buildNormalizedBundle(
      "ACC-4",
      List.of(patient),
      new AnalyzerContext(
        "bridge-connection-7f3c",
        "oe-analyzer-42",
        "genexpert-astm",
        1,
        "ASTM",
        "TCP",
        DeviceInfo.fromSenderToken("10.20.30.40", "GENEXPERT^GeneXpert^4.6.0"),
        recognition,
        "sha256:" + "2".repeat(64)
      ),
      code -> null
    );

    assertConforms(json);
    Observation observation = resource(FHIR.newJsonParser().parseResource(Bundle.class, json), Observation.class);
    org.hl7.fhir.r4.model.Extension evaluation = observation
      .getExtensionByUrl(extensionUrl("analyzer-control-recognition"))
      .getExtensionByUrl("evaluation");
    assertThat(((org.hl7.fhir.r4.model.BooleanType) evaluation
      .getExtensionByUrl("sourcePresent")
      .getValue()).booleanValue()).isFalse();
    assertThat(evaluation.getExtensionByUrl("rawValue")).isNull();
  }

  private static void assertConforms(String json) throws Exception {
    var schema = JsonSchemaFactory
      .getInstance(SpecVersion.VersionFlag.V202012)
      .getSchema(JSON.readTree(Path.of(
        "contracts",
        "analyzer",
        "v1",
        "normalized-fhir-bundle.schema.json"
      ).toFile()));
    assertThat(schema.validate(JSON.readTree(json))).isEmpty();
  }

  private static <T> T resource(Bundle bundle, Class<T> type) {
    return bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(type::isInstance)
      .map(type::cast)
      .findFirst()
      .orElseThrow();
  }

  private static String identifier(Device device, String system) {
    return device
      .getIdentifier()
      .stream()
      .filter(value -> system.equals(value.getSystem()))
      .map(value -> value.getValue())
      .findFirst()
      .orElseThrow();
  }

  private static String coding(Observation observation, String system) {
    return observation
      .getCode()
      .getCoding()
      .stream()
      .filter(value -> system.equals(value.getSystem()))
      .map(Coding::getCode)
      .findFirst()
      .orElseThrow();
  }

  private static String extensionString(Device device, String name) {
    return ((org.hl7.fhir.r4.model.StringType) device.getExtensionByUrl(extensionUrl(name)).getValue()).getValue();
  }

  private static int extensionInteger(Device device, String name) {
    return ((org.hl7.fhir.r4.model.IntegerType) device.getExtensionByUrl(extensionUrl(name)).getValue()).getValue();
  }

  private static String extensionCode(Device device, String name) {
    return ((org.hl7.fhir.r4.model.CodeType) device.getExtensionByUrl(extensionUrl(name)).getValue()).getValue();
  }

  private static String extensionUrl(String name) {
    return "https://openelis-global.org/fhir/StructureDefinition/" + name;
  }
}
