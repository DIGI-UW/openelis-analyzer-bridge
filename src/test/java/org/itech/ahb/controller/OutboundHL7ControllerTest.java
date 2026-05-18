package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Map;
import org.itech.ahb.mllp.OutboundMllpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link OutboundHL7Controller}. Wires a mocked
 * {@link OutboundMllpClient} via reflection so each test stays isolated from
 * the actual MLLP socket layer.
 */
@DisplayName("OutboundHL7Controller — POST /api/send-hl7")
class OutboundHL7ControllerTest {

    private OutboundHL7Controller controller;
    private OutboundMllpClient mockClient;

    @BeforeEach
    void setUp() throws Exception {
        controller = new OutboundHL7Controller();
        mockClient = Mockito.mock(OutboundMllpClient.class);
        Field f = OutboundHL7Controller.class.getDeclaredField("outboundMllpClient");
        f.setAccessible(true);
        f.set(controller, mockClient);
    }

    private OutboundHL7Controller.SendHl7Request newRequest() {
        OutboundHL7Controller.SendHl7Request r = new OutboundHL7Controller.SendHl7Request();
        r.host = "analyzer-mock";
        r.port = 5380;
        r.hl7Message = "MSH|^~\\&|OE2|LAB|...\rORC|NW|ACC-1\rOBR|1|ACC-1|ACC-1\r";
        r.timeoutMs = 5000;
        return r;
    }

    @Test
    @DisplayName("missing host → 400 with descriptive error")
    void missingHostIs400() {
        OutboundHL7Controller.SendHl7Request r = newRequest();
        r.host = "";
        ResponseEntity<Map<String, Object>> resp = controller.sendHl7(r);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(((String) resp.getBody().get("error")).contains("host"));
        verifyNoInteractions(mockClient);
    }

    @Test
    @DisplayName("invalid port → 400")
    void invalidPortIs400() {
        OutboundHL7Controller.SendHl7Request r = newRequest();
        r.port = 0;
        ResponseEntity<Map<String, Object>> resp = controller.sendHl7(r);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verifyNoInteractions(mockClient);
    }

    @Test
    @DisplayName("missing hl7Message → 400")
    void missingMessageIs400() {
        OutboundHL7Controller.SendHl7Request r = newRequest();
        r.hl7Message = "";
        ResponseEntity<Map<String, Object>> resp = controller.sendHl7(r);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verifyNoInteractions(mockClient);
    }

    @Test
    @DisplayName("positive ACK → 200 with ack body")
    void positiveAckReturns200() {
        OutboundMllpClient.SendResult ok = new OutboundMllpClient.SendResult(
                true, "MSH|^~\\&|...|||MSA|AA|CTRL-1\r", null, 1);
        when(mockClient.send(anyString(), anyInt(), anyString(), anyInt())).thenReturn(ok);

        ResponseEntity<Map<String, Object>> resp = controller.sendHl7(newRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(Boolean.TRUE, resp.getBody().get("success"));
        assertEquals(1, resp.getBody().get("attempts"));
        assertTrue(((String) resp.getBody().get("ack")).contains("MSA|AA|"));
        verify(mockClient).send(eq("analyzer-mock"), eq(5380), anyString(), eq(5000));
    }

    @Test
    @DisplayName("negative ACK / send failure → 502 with error body")
    void sendFailureReturns502() {
        OutboundMllpClient.SendResult fail = new OutboundMllpClient.SendResult(
                false, "MSH|...MSA|AE|CTRL-1\r", "Application error ACK (MSA|AE)", 3);
        when(mockClient.send(anyString(), anyInt(), anyString(), anyInt())).thenReturn(fail);

        ResponseEntity<Map<String, Object>> resp = controller.sendHl7(newRequest());

        assertEquals(HttpStatus.BAD_GATEWAY, resp.getStatusCode());
        assertEquals(Boolean.FALSE, resp.getBody().get("success"));
        assertEquals(3, resp.getBody().get("attempts"));
        assertEquals("Application error ACK (MSA|AE)", resp.getBody().get("error"));
    }

    @Test
    @DisplayName("timeoutMs not provided → defaults to 30000")
    void defaultTimeoutAppliedWhenAbsent() {
        OutboundHL7Controller.SendHl7Request r = newRequest();
        r.timeoutMs = null;
        OutboundMllpClient.SendResult ok = new OutboundMllpClient.SendResult(true, "MSA|AA|", null, 1);
        when(mockClient.send(anyString(), anyInt(), anyString(), anyInt())).thenReturn(ok);

        controller.sendHl7(r);

        verify(mockClient).send(anyString(), anyInt(), anyString(), eq(30_000));
    }
}
