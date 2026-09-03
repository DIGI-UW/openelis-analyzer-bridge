package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.controller.OutboundOrderController.OrderRequest;
import org.itech.ahb.mllp.OutboundMllpClient;
import org.itech.ahb.order.OutboundAstmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    private void registerAnalyzer(String connectionId, String host, int port, String protocol,
            Map<String, String> codeToLoinc) {
        AnalyzerEntry e = new AnalyzerEntry();
        e.setId("AN-1");
        e.setBridgeConnectionId(connectionId);
        e.setOutboundHost(host);
        e.setOutboundPort(port);
        e.setExpectedProtocol(protocol);
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
        when(mllp.send(eq("10.0.0.5"), eq(5380), Mockito.anyString(), anyInt()))
                .thenReturn(new OutboundMllpClient.SendResult(true, "MSA|AA|", null, 1));

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
        assertTrue(recs.getValue().stream().anyMatch(r -> r.contains("^^^MTB-RIF")),
                "ASTM order must carry the translated analyzer code MTB-RIF");
    }

    @Test
    @DisplayName("unmapped LOINC (none resolve) → 422, nothing dispatched")
    void unmappedLoinc() {
        registerAnalyzer("bridge-5", "10.0.0.5", 5380, "HL7", Map.of("WBC", "6690-2"));
        ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("bridge-5", List.of("99999-9")));
        assertEquals(422, resp.getStatusCode().value());
        verify(mllp, never()).send(Mockito.anyString(), anyInt(), Mockito.anyString(), anyInt());
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
        when(mllp.send(Mockito.anyString(), anyInt(), Mockito.anyString(), anyInt()))
                .thenReturn(new OutboundMllpClient.SendResult(false, null, "refused", 3));
        ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("bridge-5", List.of("6690-2")));
        assertEquals(502, resp.getStatusCode().value());
    }
}
