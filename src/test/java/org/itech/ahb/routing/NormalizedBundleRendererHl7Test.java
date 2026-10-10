package org.itech.ahb.routing;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.Hl7ResultRecordSelection;
import org.itech.ahb.profile.Hl7SpecimenPosition;
import org.itech.ahb.profile.ResultReading;
import org.junit.jupiter.api.Test;

/**
 * The Mindray BC-5380's own CBC message (Operator's Manual appendix C.3.3), read through its shipped
 * profile, reaches the bundle with every part in its FHIR slot.
 */
class NormalizedBundleRendererHl7Test {

  private static final FhirContext FHIR = FhirContext.forR4();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void eachObservationCarriesItsValueUnitRangeAndFlags() throws Exception {
    Map<String, Observation> observations = render();

    Observation hgb = observations.get("718-7");
    assertThat(hgb.getValueQuantity().getValue()).isEqualByComparingTo(new BigDecimal("65"));
    assertThat(hgb.getValueQuantity().getUnit()).isEqualTo("g/L");
    Observation.ObservationReferenceRangeComponent range = hgb.getReferenceRangeFirstRep();
    assertThat(range.getText()).isEqualTo("110-150");
    assertThat(range.getLow().getValue()).isEqualByComparingTo(new BigDecimal("110"));
    assertThat(range.getHigh().getValue()).isEqualByComparingTo(new BigDecimal("150"));
    assertThat(hgb.getInterpretation()).extracting(CodeableConcept::getText).containsExactly("L");
    assertThat(observations.get("6690-2").getInterpretation())
      .extracting(CodeableConcept::getText)
      .containsExactly("N");
  }

  @Test
  void theInstrumentsSettingsAndImagesAreNotObservations() throws Exception {
    assertThat(render()).doesNotContainKeys("08001", "08002", "08003", "01002", "30525-0", "15008", "15200");
  }

  private Map<String, Observation> render() throws Exception {
    ObjectNode profile;
    try (InputStream input = getClass().getClassLoader().getResourceAsStream("analyzer-profiles/mindray-bc5380.json")) {
      profile = (ObjectNode) objectMapper.readTree(input);
    }
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("analyzer-1");
    entry.setBridgeConnectionId("bridge-connection-bc5380");
    entry.setProfileId("mindray-bc5380");
    entry.setProfileRevision(1);
    entry.setExpectedProtocol("HL7");
    entry.setInboundTransport("HTTP");
    entry.setControlResultRecognition(ControlResultRecognition.fromProfile(profile.path("controlResultRecognition")));
    entry.setHl7SpecimenPosition(Hl7SpecimenPosition.fromProfile(profile.path("configDefaults")));
    entry.setHl7ResultRecordSelection(Hl7ResultRecordSelection.fromProfile(profile.path("configDefaults")));
    entry.setRecognitionFingerprint(profile.path("catalog").path("recognitionFingerprint").asText());
    entry.setResultReading(ResultReading.fromProfile(profile));
    registry.register("10.0.0.8", entry);
    String message = Files.readString(
      Path.of("src", "test", "resources", "mindray-examples", "bc5380-c.3.3.hl7"),
      StandardCharsets.UTF_8
    ).replace("\n", "\r");

    NormalizedBundleRenderer.Outcome outcome = new NormalizedBundleRenderer(registry).render(
      MessageEnvelope.builder()
        .protocol(Protocol.HL7)
        .transport(Transport.HTTP)
        .sourceId("10.0.0.8")
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
      .collect(
        Collectors.toMap(observation -> observation.getCode().getCodingFirstRep().getCode(), Function.identity())
      );
  }
}
