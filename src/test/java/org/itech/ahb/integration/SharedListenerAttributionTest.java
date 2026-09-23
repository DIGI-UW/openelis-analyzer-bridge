package org.itech.ahb.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import org.itech.ahb.lib.astm.concept.DefaultASTMMessage;
import org.itech.ahb.mllp.HapiReceivingApplication;
import org.itech.ahb.normalizer.ASTMBridgeAdapter;
import org.itech.ahb.normalizer.AnalyzerIdentifier;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.itech.ahb.outbox.FailureReason;
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
 * Several analyzers on one shared ASTM listener, from receipt to a rendered delivery: each result
 * is attributed to the right connection, and a result that cannot be attributed is kept whole in
 * the dead-message queue and attributed correctly once the configuration is fixed.
 */
class SharedListenerAttributionTest {

  private static final int SHARED_PORT = 12001;
  private static final int MLLP_PORT = 2575;

  @TempDir
  Path directory;

  private AnalyzerRuntimeRegistry registry;
  private OutboxTestSupport outbox;
  private ASTMBridgeAdapter sharedListener;
  private HapiReceivingApplication sharedMllpListener;

  @BeforeEach
  void setUp() {
    registry = new AnalyzerRuntimeRegistry();
    HTTPForwardServerConfigurationProperties http = new HTTPForwardServerConfigurationProperties();
    // Nothing listens here: these tests stop at rendering and never need OpenELIS to answer.
    http.setUri(java.net.URI.create("http://127.0.0.1:1/api/OpenELIS-Global/analyzer"));
    outbox = OutboxTestSupport.create(directory, http, registry);
    MessageNormalizer normalizer = outbox.normalizer(new AnalyzerIdentifier(registry), registry);
    sharedListener = new ASTMBridgeAdapter(normalizer, SHARED_PORT);
    sharedMllpListener = new HapiReceivingApplication(normalizer, MLLP_PORT);
  }

  @AfterEach
  void tearDown() {
    outbox.close();
  }

  @Test
  void resultsFromTwoAddressesOnOneListenerReachTheirOwnConnections() {
    register("gx-lab-a", "10.0.0.21");
    register("gx-lab-b", "10.0.0.22");

    sharedListener.handle(message("ACC-A"), "10.0.0.21");
    sharedListener.handle(message("ACC-B"), "10.0.0.22");

    assertThat(pendingConnectionFor("ACC-A")).isEqualTo("gx-lab-a");
    assertThat(pendingConnectionFor("ACC-B")).isEqualTo("gx-lab-b");
  }

  @Test
  void aSingleConnectionWithoutAHostReceivesEverythingOnItsListener() {
    register("gx-lab-a", null);

    sharedListener.handle(message("ACC-A"), "10.0.0.77");

    assertThat(pendingConnectionFor("ACC-A")).isEqualTo("gx-lab-a");
  }

  @Test
  void anUnknownAddressIsDeadLetteredWithItsPayloadAndNeverMisattributed() {
    register("gx-lab-a", "10.0.0.21");

    sharedListener.handle(message("ACC-X"), "10.0.0.99");

    OutboxEntry dead = onlyDeadLetter();
    assertThat(dead.failureReason()).isEqualTo(FailureReason.UNREGISTERED_SOURCE);
    assertThat(dead.lastError()).contains("10.0.0.99").contains("port 12001");
    assertThat(outbox.store.rawPayload(dead.id()).orElseThrow()).contains("ACC-X");
    assertThat(outbox.store.list(OutboxQuery.inState(OutboxState.PENDING, 10))).isEmpty();
  }

  @Test
  void anAmbiguousPairIsDeadLetteredAndResolvedOnRetryOnceItIsFixed() {
    register("gx-lab-a", null);
    register("gx-lab-b", null);

    sharedListener.handle(message("ACC-Y"), "10.0.0.21");

    OutboxEntry dead = onlyDeadLetter();
    assertThat(dead.failureReason()).isEqualTo(FailureReason.AMBIGUOUS_SOURCE);
    assertThat(dead.lastError()).contains("gx-lab-a").contains("gx-lab-b");
    assertThat(dead.listenerPort()).isEqualTo(SHARED_PORT);
    assertThat(outbox.store.rawPayload(dead.id()).orElseThrow()).contains("ACC-Y");

    // The operator gives the second instrument its own address; the held result is retried.
    registry.unregister("connection:gx-lab-b", "oe-gx-lab-b");
    register("gx-lab-b", "10.0.0.22");
    outbox.store.requestRetry(dead.id(), "operator", Instant.now());
    outbox.dispatcher.dispatchDue();

    assertThat(connectionFor("ACC-Y")).isEqualTo("gx-lab-a");
  }

  @Test
  void hl7ResultsOnOneMllpListenerAreSeparatedByAddressAndBySendingApplication() throws Exception {
    registerHl7("hl7-by-address", "10.0.0.31", null);
    registerHl7("hl7-lab-a", null, "GX-LAB-A");
    registerHl7("hl7-lab-b", null, "GX-LAB-B");

    sharedMllpListener.processMessage(hl7("GX-LAB-B", "ACC-H1"), metadata("10.0.0.31"));
    sharedMllpListener.processMessage(hl7("GX-LAB-A", "ACC-H2"), metadata("10.0.0.77"));
    sharedMllpListener.processMessage(hl7("GX-LAB-B", "ACC-H3"), metadata("10.0.0.77"));

    assertThat(pendingConnectionFor("ACC-H1")).as("address wins over the sender").isEqualTo("hl7-by-address");
    assertThat(pendingConnectionFor("ACC-H2")).isEqualTo("hl7-lab-a");
    assertThat(pendingConnectionFor("ACC-H3")).isEqualTo("hl7-lab-b");
  }

  private void registerHl7(String connectionId, String host, String senderId) {
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("oe-" + connectionId);
    entry.setBridgeConnectionId(connectionId);
    entry.setName(connectionId);
    entry.setProfileId("hl7-fixture");
    entry.setProfileRevision(1);
    entry.setExpectedProtocol("HL7");
    entry.setInboundTransport("TCP/IP");
    entry.setListenerPort(MLLP_PORT);
    entry.setInboundAddress(host);
    entry.setInboundSourceId(host);
    entry.setSenderId(senderId);
    entry.setControlResultRecognition(ControlResultRecognition.none());
    entry.setRecognitionFingerprint("sha256:" + "0".repeat(64));
    entry.setCodeToLoinc(Map.of("WBC", "6690-2"));
    registry.register("connection:" + connectionId, entry);
  }

  private static Message hl7(String sendingApplication, String accession) throws Exception {
    return new PipeParser().parse(
      "MSH|^~\\&|" + sendingApplication + "^GeneXpert^6.1||OE|LAB|20260922120000||ORU^R01|" + accession + "|P|2.5.1\r" +
      "PID|1||PAT-1\r" +
      "OBR|1||" + accession + "|CBC\r" +
      "OBX|1|NM|WBC||7.5|10^3/uL||||F"
    );
  }

  private static Map<String, Object> metadata(String peer) {
    Map<String, Object> metadata = new java.util.HashMap<>();
    metadata.put("SENDING_IP", peer);
    return metadata;
  }

  private void register(String connectionId, String host) {
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("oe-" + connectionId);
    entry.setBridgeConnectionId(connectionId);
    entry.setName(connectionId);
    entry.setProfileId("genexpert-astm");
    entry.setProfileRevision(1);
    entry.setExpectedProtocol("ASTM");
    entry.setInboundTransport("TCP/IP");
    entry.setListenerPort(SHARED_PORT);
    entry.setInboundAddress(host);
    entry.setInboundSourceId(host);
    entry.setControlResultRecognition(ControlResultRecognition.none());
    entry.setRecognitionFingerprint("sha256:" + "0".repeat(64));
    entry.setAstmResultRecordSelection(AstmResultRecordSelection.all());
    entry.setCodeToLoinc(Map.of("WBC", "6690-2"));
    registry.register("connection:" + connectionId, entry);
  }

  private static DefaultASTMMessage message(String accession) {
    return new DefaultASTMMessage(
      "H|\\^&|||GENEXPERT^GeneXpert^4.6.0|||||LIS||P|1394-97|20260922120000\r" +
      "P|1||PAT-1\r" +
      "O|1|" + accession + "||^^^WBC\r" +
      "R|1|^^^WBC|7.5|10^3/uL||N||F\r" +
      "L|1|N\r"
    );
  }

  private String pendingConnectionFor(String accession) {
    return outbox.store
      .list(OutboxQuery.inState(OutboxState.PENDING, 10))
      .stream()
      .filter(entry -> accession.equals(entry.accession()))
      .findFirst()
      .orElseThrow(() -> new AssertionError("no rendered delivery for " + accession))
      .connectionId();
  }

  private String connectionFor(String accession) {
    List<OutboxEntry> all = outbox.store.list(OutboxQuery.all(20));
    return all
      .stream()
      .filter(entry -> accession.equals(entry.accession()) && entry.connectionId() != null)
      .findFirst()
      .orElseThrow(() -> new AssertionError("no delivery for " + accession + " in " + all))
      .connectionId();
  }

  private OutboxEntry onlyDeadLetter() {
    List<OutboxEntry> dead = outbox.store.list(OutboxQuery.inState(OutboxState.DMQ, 10));
    assertThat(dead).hasSize(1);
    return dead.get(0);
  }
}
