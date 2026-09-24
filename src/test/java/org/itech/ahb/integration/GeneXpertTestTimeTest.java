package org.itech.ahb.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Observation;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.lib.astm.concept.DefaultASTMMessage;
import org.itech.ahb.normalizer.ASTMBridgeAdapter;
import org.itech.ahb.normalizer.AnalyzerIdentifier;
import org.itech.ahb.outbox.OutboxEntry;
import org.itech.ahb.outbox.OutboxQuery;
import org.itech.ahb.outbox.OutboxState;
import org.itech.ahb.outbox.OutboxTestSupport;
import org.itech.ahb.profile.AstmResultRecordSelection;
import org.itech.ahb.profile.ControlResultRecognition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A GeneXpert result keeps the time the instrument performed the test, from the ASTM listener's
 * message handler to the FHIR rendered for OpenELIS, which otherwise records the import time.
 *
 * <p>The message follows the record layout of Cepheid's LIS Interface Protocol Specification
 * (302-2261): R.12 is the test start time and R.13 its completion time, and only records whose
 * R.3.5 names the assay are results (the shipped profile's result-record selection).
 */
class GeneXpertTestTimeTest {

  private static final int PORT = 12001;
  private static final FhirContext FHIR = FhirContext.forR4Cached();

  @TempDir
  Path directory;

  private AnalyzerRuntimeRegistry registry;
  private OutboxTestSupport outbox;
  private ASTMBridgeAdapter listener;
  private TimeZone originalZone;

  @BeforeEach
  void setUp() throws Exception {
    originalZone = TimeZone.getDefault();
    // The site zone of a real deployment, UTC+10 without daylight saving, so the offset is asserted.
    TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Port_Moresby"));
    registry = new AnalyzerRuntimeRegistry();
    HTTPForwardServerConfigurationProperties http = new HTTPForwardServerConfigurationProperties();
    // Nothing listens here: these tests stop at rendering and never need OpenELIS to answer.
    http.setUri(java.net.URI.create("http://127.0.0.1:1/api/OpenELIS-Global/analyzer"));
    outbox = OutboxTestSupport.create(directory, http, registry);
    listener = new ASTMBridgeAdapter(outbox.normalizer(new AnalyzerIdentifier(registry), registry), PORT);
    registerGeneXpert();
  }

  @AfterEach
  void tearDown() {
    outbox.close();
    TimeZone.setDefault(originalZone);
  }

  @Test
  void theRenderedResultCarriesTheInstrumentsCompletionTime() {
    listener.handle(message("TEST-GX-0001", "20251021144507", "20251021161230"), "10.0.0.21");

    DateTimeType effective = effectiveTimeOf("TEST-GX-0001");
    assertThat(effective.getValueAsString()).isEqualTo("2025-10-21T16:12:30+10:00");
    assertThat(effective.getPrecision()).isEqualTo(TemporalPrecisionEnum.SECOND);
  }

  @Test
  void aCompletionTimeOnTheMinuteIsKept() {
    listener.handle(message("TEST-GX-0004", "20251021144500", "20251021161200"), "10.0.0.21");

    assertThat(effectiveTimeOf("TEST-GX-0004").getValueAsString()).isEqualTo("2025-10-21T16:12:00+10:00");
  }

  @Test
  void aResultWithoutATestTimeCarriesNoneRatherThanAGuess() {
    listener.handle(message("TEST-GX-0003", "", ""), "10.0.0.21");

    assertThat(observationOf("TEST-GX-0003").hasEffective()).isFalse();
  }

  private void registerGeneXpert() throws Exception {
    JsonNode profile;
    try (InputStream json = getClass().getResourceAsStream("/analyzer-profiles/genexpert-astm-v5.json")) {
      profile = new ObjectMapper().readTree(json);
    }
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("oe-gx");
    entry.setBridgeConnectionId("gx");
    entry.setName("gx");
    entry.setProfileId("genexpert-astm");
    entry.setProfileRevision(5);
    entry.setExpectedProtocol("ASTM");
    entry.setInboundTransport("TCP/IP");
    entry.setListenerPort(PORT);
    entry.setControlResultRecognition(ControlResultRecognition.fromProfile(profile.path("controlResultRecognition")));
    entry.setRecognitionFingerprint("sha256:" + "0".repeat(64));
    entry.setAstmResultRecordSelection(AstmResultRecordSelection.fromProfile(profile.path("configDefaults")));
    entry.setCodeToLoinc(Map.of());
    registry.register("connection:gx", entry);
  }

  private static DefaultASTMMessage message(String sampleId, String started, String completed) {
    return new DefaultASTMMessage(
      "H|@^\\|TEST-MSG-0001||TESTLAB^GeneXpert^6.2|||||TEST LAB||P|1394-97|20260923155007\r" +
      "P|1|||TEST PATIENT|^^^^\r" +
      "O|1|" + sampleId + "|Cepheid-TEST-0001|^^^MTB-RIF_ULTRA 2|R|" + started + "|||||||||ORH||||||||||F\r" +
      "R|1|^MTB-RIF_ULTRA 2^^^Xpert MTB-RIF Ultra^1^MTB^|MTB DETECTED LOW^|||||F||Test Operator|" + started +
      "|" + completed + "|Cepheid-TEST^000001^000000001^000000001^00001^20270110|\r" +
      "R|2|^^^MTB-RIF_ULTRA 2^^^SPC^Ct|^25.9|||\r" +
      "L|1|N\r"
    );
  }

  private Observation observationOf(String sampleId) {
    List<OutboxEntry> rendered = outbox.store.list(OutboxQuery.inState(OutboxState.PENDING, 10));
    OutboxEntry delivery = rendered
      .stream()
      .filter(entry -> sampleId.equals(entry.accession()))
      .findFirst()
      .orElseThrow(() -> new AssertionError("no rendered delivery for " + sampleId + " in " + rendered));
    Bundle bundle = FHIR.newJsonParser().parseResource(Bundle.class, outbox.store.fhirPayload(delivery.id()).orElseThrow());
    List<Observation> observations = bundle
      .getEntry()
      .stream()
      .map(Bundle.BundleEntryComponent::getResource)
      .filter(Observation.class::isInstance)
      .map(Observation.class::cast)
      .toList();
    assertThat(observations).as("only the assay record is a result").hasSize(1);
    return observations.get(0);
  }

  private DateTimeType effectiveTimeOf(String sampleId) {
    Observation observation = observationOf(sampleId);
    assertThat(observation.hasEffectiveDateTimeType()).as("the result carries a test time").isTrue();
    return observation.getEffectiveDateTimeType();
  }
}
