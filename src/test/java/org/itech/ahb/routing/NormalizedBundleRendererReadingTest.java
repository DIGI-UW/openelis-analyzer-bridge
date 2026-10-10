package org.itech.ahb.routing;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.Map;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.BaselineProfileFixtures;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.ResultReading;
import org.junit.jupiter.api.Test;

/** A saved connection's settings change how the pinned profile reads what its instrument sends. */
class NormalizedBundleRendererReadingTest {

  private static final FhirContext FHIR = FhirContext.forR4();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void aCodeTheConnectionOverridesIsTranslatedToTheProfilesCodeBeforeItIsBundled() throws Exception {
    ObjectNode values = objectMapper.createObjectNode();
    values.putObject("codeOverrides").put("HIVVL", "HIVU");

    Observation observation = render(values, "HIVU", "^1009.64");

    assertThat(observation.getCode().getCodingFirstRep().getCode()).isEqualTo("HIVVL");
  }

  @Test
  void aResultUnderTheLabsOwnCodeKeepsTheLoincOfTheAssay() throws Exception {
    ObjectNode values = objectMapper.createObjectNode();
    values.putObject("codeOverrides").put("HIVVL", "HIVU");

    // The connection's table, as the runtime builds it: the lab's code, not the profile's.
    Observation observation = render(values, Map.of("HIVU", "20447-9"), "HIVU", "^1009.64");

    assertThat(observation.getCode().getCoding()).anySatisfy(coding -> {
      assertThat(coding.getSystem()).isEqualTo("http://loinc.org");
      assertThat(coding.getCode()).isEqualTo("20447-9");
    });
  }

  @Test
  void theStatusTheInstrumentGaveTheResultIsCarried() throws Exception {
    Observation observation = render(objectMapper.createObjectNode(), "HIVVL", "^1009.64");

    assertThat(observation.getStatus()).isEqualTo(Observation.ObservationStatus.FINAL);
    assertThat(
      observation
        .getExtensionByUrl("https://openelis-global.org/fhir/StructureDefinition/analyzer-result-status")
        .getValue()
        .primitiveValue()
    ).isEqualTo("F");
  }

  @Test
  void anOverrideSavedWithSpacesStillTranslatesTheCodeTheInstrumentSends() throws Exception {
    ObjectNode values = objectMapper.createObjectNode();
    values.putObject("codeOverrides").put("HIVVL", " HIVU ");

    Observation observation = render(values, "HIVU", "^1009.64");

    assertThat(observation.getCode().getCodingFirstRep().getCode()).isEqualTo("HIVVL");
  }

  @Test
  void theRangeTheInstrumentReportedTheResultAgainstIsCarried() throws Exception {
    Observation observation = render(objectMapper.createObjectNode(), "HIVVL", "^1009.64");

    Observation.ObservationReferenceRangeComponent range = observation.getReferenceRangeFirstRep();
    assertThat(range.getText()).isEqualTo("40.00 to 10000000.00");
    assertThat(range.getType().getText()).isEqualTo("Instrument-reported range");
    assertThat(range.getLow().getValue()).isEqualByComparingTo(new BigDecimal("40"));
    assertThat(range.getHigh().getValue()).isEqualByComparingTo(new BigDecimal("10000000"));
    assertThat(range.getLow().getUnit()).isEqualTo("copies/mL");
  }

  @Test
  void aCodeNoOverrideNamesIsBundledAsTheInstrumentSentIt() throws Exception {
    Observation observation = render(objectMapper.createObjectNode(), "HIVU", "^1009.64");

    assertThat(observation.getCode().getCodingFirstRep().getCode()).isEqualTo("HIVU");
  }

  @Test
  void aConnectionsDecimalCommaIsReadAsAnumberAndTheRawTextIsKept() throws Exception {
    ObjectNode values = objectMapper.createObjectNode();
    values.put("numberFormat", ",");

    Observation observation = render(values, "HIVVL", "^40,00");

    assertThat(observation.getValueQuantity().getValue()).isEqualByComparingTo(new BigDecimal("40"));
    Extension raw = observation.getExtensionByUrl(
      "https://openelis-global.org/fhir/StructureDefinition/analyzer-raw-value"
    );
    assertThat(raw.getValue().primitiveValue()).isEqualTo("40,00");
  }

  private Observation render(ObjectNode connectionValues, String codeSent, String data) throws Exception {
    return render(connectionValues, Map.of(), codeSent, data);
  }

  private Observation render(
    ObjectNode connectionValues,
    Map<String, String> codeToLoinc,
    String codeSent,
    String data
  ) throws Exception {
    ObjectNode profile = BaselineProfileFixtures.genexpertHivViralLoad(objectMapper);
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("analyzer-1");
    entry.setBridgeConnectionId("bridge-connection-7f3c");
    entry.setProfileId("genexpert-astm");
    entry.setProfileRevision(8);
    entry.setExpectedProtocol("ASTM");
    entry.setInboundTransport("HTTP");
    entry.setControlResultRecognition(ControlResultRecognition.none());
    entry.setAstmResultRecordSelection(AstmResultRecordSelection.all());
    entry.setRecognitionFingerprint("sha256:" + "0".repeat(64));
    entry.setResultReading(ResultReading.fromProfile(profile, connectionValues));
    entry.setCodeToLoinc(codeToLoinc);
    registry.register("10.0.0.7", entry);
    String message =
      "H|@^\\|URM-1||GeneXpert PC^GeneXpert^1.0|||||GX||P|1394-97|20221202104017\r" +
      "P|1||||^^^^\r" +
      "O|1|ACC-1||^^^" +
      codeSent +
      "|R|20221115035816|||||||||ORH||||||||||F\r" +
      "R|1|^^^" +
      codeSent +
      "^Xpert HIV-1 Viral Load XC^3^^|" +
      data +
      "|copies/mL|40.00 to 10000000.00|N||F||<None>|20221115035816|20221115052812|X\r" +
      "L|1|N\r";
    NormalizedBundleRenderer.Outcome outcome = new NormalizedBundleRenderer(registry).render(
      MessageEnvelope.builder()
        .protocol(Protocol.ASTM)
        .transport(Transport.HTTP)
        .sourceId("10.0.0.7")
        .resolvedAnalyzerId("analyzer-1")
        .rawMessage(message)
        .build(),
      "http://localhost/analyzer"
    );

    assertThat(outcome).isInstanceOf(NormalizedBundleRenderer.Outcome.Rendered.class);
    Bundle bundle = FHIR.newJsonParser()
      .parseResource(
        Bundle.class,
        ((NormalizedBundleRenderer.Outcome.Rendered) outcome).deliveries().get(0).fhirJson()
      );
    return bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Observation.class::isInstance)
      .map(Observation.class::cast)
      .findFirst()
      .orElseThrow();
  }
}
