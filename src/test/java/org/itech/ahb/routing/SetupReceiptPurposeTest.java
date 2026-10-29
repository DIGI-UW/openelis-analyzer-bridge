package org.itech.ahb.routing;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Device;
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

class SetupReceiptPurposeTest {

  @Test
  void delayedSetupAndItsRetryKeepReceiptPurposeAfterRoutineActivation() throws Exception {
    var registry = new AnalyzerRuntimeRegistry();
    var entry = new AnalyzerEntry();
    entry.setId("setup-analyzer");
    entry.setBridgeConnectionId("setup-connection");
    entry.setProfileId("genexpert-astm");
    entry.setProfileRevision(1);
    entry.setExpectedProtocol("ASTM");
    entry.setInboundTransport("HTTP");
    entry.setReceptionPurpose("SETUP");
    entry.setControlResultRecognition(ControlResultRecognition.none());
    entry.setAstmResultRecordSelection(AstmResultRecordSelection.all());
    entry.setRecognitionFingerprint("sha256:" + "0".repeat(64));
    entry.setResultReading(
      ResultReading.fromProfile(
        BaselineProfileFixtures.genexpertHivViralLoad(new ObjectMapper()),
        new ObjectMapper().createObjectNode()
      )
    );
    registry.register("10.0.0.7", entry);
    var envelope = MessageEnvelope.builder()
      .protocol(Protocol.ASTM)
      .transport(Transport.HTTP)
      .sourceId("10.0.0.7")
      .rawMessage(
        "H|@^\\|URM-1||GeneXpert PC^GeneXpert^1.0|||||GX||P|1394-97|20221202104017\r" +
        "P|1||||^^^^\rO|1|SETUP-1||^^^HIVVL|R|20221115035816|||||||||ORH||||||||||F\r" +
        "R|1|^^^HIVVL^Xpert HIV-1 Viral Load XC^3^^|^1009.64|copies/mL|40 to 10000000|N||F||<None>|20221115035816|20221115052812|X\rL|1|N\r"
      )
      .build();
    var renderer = new NormalizedBundleRenderer(registry);
    String retained = renderer.capture(envelope);
    entry.setReceptionPurpose("ROUTINE");
    registry.register("10.0.0.7", entry);
    assertThat(ReceivedInterpretation.read(retained).analyzer().getReceptionPurpose()).isEqualTo("SETUP");
    var delayed = rendered(renderer.render(envelope, "http://openelis/analyzer", retained));
    var retry = rendered(renderer.render(envelope, "http://openelis/analyzer", retained));
    assertThat(purpose(delayed)).isEqualTo("SETUP");
    assertThat(purpose(retry)).isEqualTo("SETUP");
    assertThat(retry.getIdentifier().getValue()).isEqualTo(delayed.getIdentifier().getValue());
    assertThat(purpose(rendered(renderer.render(envelope, "http://openelis/analyzer")))).isEqualTo("ROUTINE");
  }

  private static Bundle rendered(NormalizedBundleRenderer.Outcome outcome) {
    assertThat(outcome).isInstanceOf(NormalizedBundleRenderer.Outcome.Rendered.class);
    return FhirContext.forR4()
      .newJsonParser()
      .parseResource(
        Bundle.class,
        ((NormalizedBundleRenderer.Outcome.Rendered) outcome).deliveries().get(0).fhirJson()
      );
  }

  private static String purpose(Bundle bundle) {
    var device = bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Device.class::isInstance)
      .map(Device.class::cast)
      .findFirst()
      .orElseThrow();
    String root = "https://openelis-global.org/fhir/StructureDefinition/";
    assertThat(device.getExtensionByUrl(root + "analyzer-contract-version").getValue().primitiveValue()).isEqualTo(
      "1.2"
    );
    return device.getExtensionByUrl(root + "analyzer-delivery-purpose").getValue().primitiveValue();
  }
}
