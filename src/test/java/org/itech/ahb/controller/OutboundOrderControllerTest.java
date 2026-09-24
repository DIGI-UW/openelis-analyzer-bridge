package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.connection.AstmConnectionListeners;
import org.itech.ahb.connection.BridgeAnalyzerConnectionRuntime;
import org.itech.ahb.connection.SerialConnectionListeners;
import org.itech.ahb.controller.OutboundOrderController.OrderRequest;
import org.itech.ahb.mllp.OutboundMllpClient;
import org.itech.ahb.order.OutboundAstmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

/**
 * M4: OE2 sends a LOINC order; the bridge translates LOINC→analyzer code via the
 * registered map (M1) and dispatches the protocol message. This is the proof
 * that OE2 stays analyzer-agnostic — LOINC in, analyzer codes on the wire.
 */
@DisplayName("OutboundOrderController — LOINC order → translate → dispatch")
class OutboundOrderControllerTest {

  private AnalyzerRuntimeRegistry registry;
  private OutboundMllpClient mllp;
  private OutboundAstmClient astm;
  private OutboundOrderController controller;

  @BeforeEach
  void setUp() {
    registry = new AnalyzerRuntimeRegistry();
    mllp = Mockito.mock(OutboundMllpClient.class);
    astm = Mockito.mock(OutboundAstmClient.class);
    controller = new OutboundOrderController(registry, mllp, astm);
  }

  private void registerAnalyzer(
    String connectionId,
    String host,
    int port,
    String protocol,
    Map<String, String> codeToLoinc
  ) {
    AnalyzerEntry e = new AnalyzerEntry();
    e.setId("AN-1");
    e.setBridgeConnectionId(connectionId);
    e.setOutboundHost(host);
    e.setOutboundPort(port);
    e.setExpectedProtocol(protocol);
    e.setOutboundOrdersSupported(true);
    e.setCodeToLoinc(codeToLoinc);
    registry.register("connection:" + connectionId, e);
  }

  private OrderRequest req(String connectionId, List<String> loincCodes) {
    OrderRequest r = new OrderRequest();
    r.connectionId = connectionId;
    r.order = new OutboundOrderController.ClinicalOrder();
    r.order.accessionNumber = "ACC-1";
    r.order.patientId = "PAT-9";
    r.order.loincCodes = loincCodes;
    return r;
  }

  @Test
  @DisplayName("HL7: LOINC translated to analyzer code, ORM sent via MLLP")
  void hl7OrderTranslatesAndSends() {
    registerAnalyzer("bridge-5", "10.0.0.5", 5380, "HL7", Map.of("WBC", "6690-2"));
    when(mllp.send(eq("10.0.0.5"), eq(5380), Mockito.anyString(), anyInt())).thenReturn(
      new OutboundMllpClient.SendResult(true, "MSA|AA|", null, 1)
    );

    ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("bridge-5", List.of("6690-2")));

    assertEquals(200, resp.getStatusCode().value());
    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    verify(mllp).send(eq("10.0.0.5"), eq(5380), msg.capture(), anyInt());
    assertTrue(msg.getValue().contains("^^^WBC"), "ORM must carry the translated analyzer code WBC");
    assertTrue(msg.getValue().contains("ORM^O01"));
    verify(astm, never()).send(Mockito.anyString(), anyInt(), Mockito.anyList(), anyInt());
  }

  @Test
  @DisplayName("ASTM: LOINC translated to analyzer code, records sent over TCP")
  void astmOrderTranslatesAndSends() {
    registerAnalyzer("bridge-6", "10.0.0.6", 9600, "ASTM", Map.of("MTB-RIF", "85362-2"));
    when(astm.send(eq("10.0.0.6"), eq(9600), Mockito.anyList(), anyInt())).thenReturn(true);

    ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("bridge-6", List.of("85362-2")));

    assertEquals(200, resp.getStatusCode().value());
    ArgumentCaptor<List<String>> recs = ArgumentCaptor.forClass(List.class);
    verify(astm).send(eq("10.0.0.6"), eq(9600), recs.capture(), anyInt());
    assertTrue(
      recs.getValue().stream().anyMatch(r -> r.contains("^^^MTB-RIF")),
      "ASTM order must carry the translated analyzer code MTB-RIF"
    );
  }

  @Test
  @DisplayName("unmapped LOINC (none resolve) → 422, nothing dispatched")
  void unmappedLoinc() {
    registerAnalyzer("bridge-5", "10.0.0.5", 5380, "HL7", Map.of("WBC", "6690-2"));
    ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("bridge-5", List.of("99999-9")));
    assertEquals(422, resp.getStatusCode().value());
    verify(mllp, never()).send(Mockito.anyString(), anyInt(), Mockito.anyString(), anyInt());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { " ", "\t\n" })
  void missingAccessionIsRejectedBeforeDispatch(String accession) {
    registerAnalyzer("bridge-6", "192.0.2.10", 9101, "ASTM", Map.of("MTB-RIF", "85362-2"));
    OrderRequest request = req("bridge-6", List.of("85362-2"));
    request.order.accessionNumber = accession;

    var response = controller.sendOrder(request);

    assertEquals(400, response.getStatusCode().value());
    assertEquals("accessionNumber is required", response.getBody().get("error"));
    Mockito.verifyNoInteractions(astm, mllp);
  }

  @Test
  void dispatchesToEachDurableClientSharingTheSameHost() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode profile = (ObjectNode) mapper.readTree(
      getClass().getResourceAsStream("/analyzer-profiles/genexpert-astm.json")
    );
    BridgeAnalyzerConnectionRuntime runtime = new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      Mockito.mock(AstmConnectionListeners.class),
      Mockito.mock(SerialConnectionListeners.class)
    );
    for (int port : List.of(9101, 9102)) {
      ObjectNode connection = mapper.createObjectNode();
      connection
        .put("connectionId", "bridge-" + port)
        .put("clientAnalyzerId", "oe-" + port)
        .put("displayName", "Bench " + port);
      connection
        .putObject("profileRef")
        .put("profileId", profile.path("profileMeta").path("id").asText())
        .put("revision", profile.path("catalog").path("revision").asInt());
      connection.putObject("values").setAll((ObjectNode) profile.path("configDefaults").deepCopy());
      connection.withObject("values").put("connectionRole", "CLIENT").put("host", "192.0.2.10").put("port", port);
      runtime.activate(connection, profile);
    }
    when(astm.send(eq("192.0.2.10"), anyInt(), Mockito.anyList(), anyInt())).thenReturn(true);

    for (int port : List.of(9101, 9102)) {
      var response = controller.sendOrder(req("bridge-" + port, List.of("85362-2")));
      assertEquals(200, response.getStatusCode().value());
      verify(astm).send(eq("192.0.2.10"), eq(port), Mockito.anyList(), anyInt());
    }
    Mockito.verifyNoInteractions(mllp);
  }

  /** Real runtime capability resolution and controller rejection; protocol clients are mocks to detect forbidden dispatch. */
  @ParameterizedTest
  @ValueSource(strings = { "SERVER", "CLIENT" })
  void resultsOnlyConnectionCannotDispatchOrdersEvenWhenTheProfileSupportsThem(String role) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode profile = (ObjectNode) mapper.readTree(
      getClass().getResourceAsStream("/analyzer-profiles/genexpert-astm-v5.json")
    );
    ObjectNode connection = mapper
      .createObjectNode()
      .put("connectionId", "results-only")
      .put("clientAnalyzerId", "oe-results-only")
      .put("displayName", "Results only");
    connection.putObject("profileRef").put("profileId", "genexpert-astm").put("revision", 5);
    connection.putObject("values").setAll((ObjectNode) profile.path("configDefaults").deepCopy());
    connection.withObject("values").put("connectionRole", role).put("host", "192.0.2.10").put("port", 9101);
    new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      Mockito.mock(AstmConnectionListeners.class),
      Mockito.mock(SerialConnectionListeners.class)
    ).activate(connection, profile);
    var response = controller.sendOrder(req("results-only", List.of("85362-2")));
    assertTrue(response.getStatusCode().is4xxClientError());
    assertEquals(false, response.getBody().get("dispatched"));
    Mockito.verifyNoInteractions(astm, mllp);
  }

  @ParameterizedTest
  @ValueSource(strings = { "outboundOrders", "supports_lis_initiated" })
  void profileCanForbidOrdersEvenWithAnOutboundEndpoint(String disabledCapability) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode profile = (ObjectNode) mapper.readTree(
      getClass().getResourceAsStream("/analyzer-profiles/genexpert-astm.json")
    );
    profile
      .withObject(disabledCapability.equals("outboundOrders") ? "capabilities" : "communication")
      .put(disabledCapability, false);
    ObjectNode connection = mapper
      .createObjectNode()
      .put("connectionId", "no-orders")
      .put("clientAnalyzerId", "oe")
      .put("displayName", "Inbound only");
    connection
      .putObject("profileRef")
      .put("profileId", profile.path("profileMeta").path("id").asText())
      .put("revision", 1);
    connection.putObject("values").setAll((ObjectNode) profile.path("configDefaults").deepCopy());
    connection.withObject("values").put("connectionRole", "CLIENT").put("host", "192.0.2.10").put("port", 9101);
    new BridgeAnalyzerConnectionRuntime(
      registry,
      null,
      Mockito.mock(AstmConnectionListeners.class),
      Mockito.mock(SerialConnectionListeners.class)
    ).activate(connection, profile);
    var response = controller.sendOrder(req("no-orders", List.of("85362-2")));
    assertEquals(400, response.getStatusCode().value());
    Mockito.verifyNoInteractions(astm, mllp);
  }

  @Test
  @DisplayName("unknown Bridge connection → 422")
  void unknownConnection() {
    ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("bridge-missing", List.of("6690-2")));
    assertEquals(422, resp.getStatusCode().value());
  }

  @Test
  @DisplayName("connection without an outbound endpoint → 422")
  void connectionWithoutOutboundEndpoint() {
    registerAnalyzer("bridge-server", null, 0, "ASTM", Map.of("MTB-RIF", "85362-2"));

    ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("bridge-server", List.of("85362-2")));

    assertEquals(422, resp.getStatusCode().value());
    assertTrue(String.valueOf(resp.getBody().get("error")).contains("outbound endpoint"));
    verify(astm, never()).send(Mockito.anyString(), anyInt(), Mockito.anyList(), anyInt());
  }

  @Test
  @DisplayName("transport failure → 502")
  void transportFailure() {
    registerAnalyzer("bridge-5", "10.0.0.5", 5380, "HL7", Map.of("WBC", "6690-2"));
    when(mllp.send(Mockito.anyString(), anyInt(), Mockito.anyString(), anyInt())).thenReturn(
      new OutboundMllpClient.SendResult(false, null, "refused", 3)
    );
    ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("bridge-5", List.of("6690-2")));
    assertEquals(502, resp.getStatusCode().value());
  }
}
