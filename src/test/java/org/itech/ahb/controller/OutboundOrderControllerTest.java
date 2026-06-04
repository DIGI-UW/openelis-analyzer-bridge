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
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
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

    private AnalyzerRegistryConfig registry;
    private OutboundMllpClient mllp;
    private OutboundAstmClient astm;
    private OutboundOrderController controller;

    @BeforeEach
    void setUp() {
        registry = new AnalyzerRegistryConfig();
        mllp = Mockito.mock(OutboundMllpClient.class);
        astm = Mockito.mock(OutboundAstmClient.class);
        controller = new OutboundOrderController(registry, mllp, astm);
    }

    private void registerAnalyzer(String host, String protocol, Map<String, String> codeToLoinc) {
        AnalyzerEntry e = new AnalyzerEntry();
        e.setId("AN-1");
        e.setExpectedProtocol(protocol);
        e.setCodeToLoinc(codeToLoinc);
        registry.register(host, e);
    }

    private OrderRequest req(String host, int port, String protocol, List<String> loincCodes) {
        OrderRequest r = new OrderRequest();
        r.host = host;
        r.port = port;
        r.protocol = protocol;
        r.accessionNumber = "ACC-1";
        r.patientId = "PAT-9";
        r.loincCodes = loincCodes;
        return r;
    }

    @Test
    @DisplayName("HL7: LOINC translated to analyzer code, ORM sent via MLLP")
    void hl7OrderTranslatesAndSends() {
        registerAnalyzer("10.0.0.5", "HL7", Map.of("WBC", "6690-2"));
        when(mllp.send(eq("10.0.0.5"), eq(5380), Mockito.anyString(), anyInt()))
                .thenReturn(new OutboundMllpClient.SendResult(true, "MSA|AA|", null, 1));

        ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("10.0.0.5", 5380, "HL7", List.of("6690-2")));

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
        registerAnalyzer("10.0.0.6", "ASTM", Map.of("MTB-RIF", "85362-2"));
        when(astm.send(eq("10.0.0.6"), eq(9600), Mockito.anyList(), anyInt())).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("10.0.0.6", 9600, "ASTM", List.of("85362-2")));

        assertEquals(200, resp.getStatusCode().value());
        ArgumentCaptor<List<String>> recs = ArgumentCaptor.forClass(List.class);
        verify(astm).send(eq("10.0.0.6"), eq(9600), recs.capture(), anyInt());
        assertTrue(recs.getValue().stream().anyMatch(r -> r.contains("^^^MTB-RIF")),
                "ASTM order must carry the translated analyzer code MTB-RIF");
    }

    @Test
    @DisplayName("unmapped LOINC (none resolve) → 422, nothing dispatched")
    void unmappedLoinc() {
        registerAnalyzer("10.0.0.5", "HL7", Map.of("WBC", "6690-2"));
        ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("10.0.0.5", 5380, "HL7", List.of("99999-9")));
        assertEquals(422, resp.getStatusCode().value());
        verify(mllp, never()).send(Mockito.anyString(), anyInt(), Mockito.anyString(), anyInt());
    }

    @Test
    @DisplayName("unregistered analyzer (no mapping) → 422")
    void unregisteredAnalyzer() {
        ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("10.9.9.9", 5380, "HL7", List.of("6690-2")));
        assertEquals(422, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("transport failure → 502")
    void transportFailure() {
        registerAnalyzer("10.0.0.5", "HL7", Map.of("WBC", "6690-2"));
        when(mllp.send(Mockito.anyString(), anyInt(), Mockito.anyString(), anyInt()))
                .thenReturn(new OutboundMllpClient.SendResult(false, null, "refused", 3));
        ResponseEntity<Map<String, Object>> resp = controller.sendOrder(req("10.0.0.5", 5380, "HL7", List.of("6690-2")));
        assertEquals(502, resp.getStatusCode().value());
    }
}
