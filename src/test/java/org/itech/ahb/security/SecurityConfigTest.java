package org.itech.ahb.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for bridge security configuration (M7.1).
 * <p>
 * Verifies that the management APIs require authentication, that the actuator health status
 * remains publicly accessible, and that /input admits only active HTTP connections' senders.
 * </p>
 */
@SpringBootTest(properties = {
    "bridge.security.username=testuser",
    "bridge.security.password=testpass",
    "org.itech.ahb.mllp.enabled=false",
    "bridge.file.enabled=false",
    "management.endpoints.web.exposure.include=health,info,prometheus,metrics,loggers"
})
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessageNormalizer mockNormalizer;

    private static final String SAMPLE_ASTM = "H|\\^&|||HOST^1.0|||||||LIS2-A2|20260206\r"
            + "P|1||||Doe^John\rL|1|N";

    @Nested
    @DisplayName("/input endpoint: an analyzer transport identified by its sender")
    class InputEndpointTests {

        @Autowired
        private org.itech.ahb.connection.AnalyzerRuntimeRegistry registry;

        @Test
        @DisplayName("A sender without an active HTTP connection is refused with 403")
        void unknownSenderIsRefused() throws Exception {
            mockMvc.perform(post("/input")
                    .content(SAMPLE_ASTM)
                    .contentType(MediaType.TEXT_PLAIN))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("Credentials do not open HTTP input to an unknown sender")
        void credentialsDoNotAdmitAnUnknownSender() throws Exception {
            mockMvc.perform(post("/input")
                    .with(httpBasic("testuser", "testpass"))
                    .content(SAMPLE_ASTM)
                    .contentType(MediaType.TEXT_PLAIN))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("An active HTTP connection's analyzer posts without a credential")
        void activeHttpSenderNeedsNoCredential() throws Exception {
            when(mockNormalizer.process(any(MessageEnvelope.class))).thenReturn(true);
            var entry = new org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry();
            entry.setId("http-analyzer");
            entry.setInboundTransport("HTTP");
            entry.setInboundSourceId("127.0.0.1");
            registry.register("connection:http-analyzer", entry);
            try {
                mockMvc.perform(post("/input")
                        .content(SAMPLE_ASTM)
                        .contentType("application/x-astm"))
                        .andExpect(status().isOk());
            } finally {
                registry.unregister("connection:http-analyzer", "http-analyzer");
            }
        }
    }

    @Nested
    @DisplayName("/api/profiles authentication")
    class ProfileApiTests {

        @Test
        @DisplayName("Unauthenticated profile catalog read returns 401")
        void unauthenticatedProfileCatalogReturns401() throws Exception {
            mockMvc.perform(get("/api/profiles"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Authenticated profile catalog read succeeds")
        void authenticatedProfileCatalogSucceeds() throws Exception {
            mockMvc.perform(get("/api/profiles")
                    .with(httpBasic("testuser", "testpass")))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("/api/connections management authentication")
    class AnalyzerConnectionApiTests {

        @Test
        @DisplayName("Unauthenticated connection create returns 401")
        void unauthenticatedConnectionCreateReturns401() throws Exception {
            mockMvc.perform(post("/api/connections")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Unauthenticated analyzer probe returns 401")
        void unauthenticatedAnalyzerProbeReturns401() throws Exception {
            mockMvc.perform(post("/api/connections/77/probe"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Authenticated analyzer probe reaches the controller")
        void authenticatedAnalyzerProbeReachesController() throws Exception {
            mockMvc.perform(post("/api/connections/77/probe")
                    .with(httpBasic("testuser", "testpass"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Actuator endpoint access")
    class ActuatorTests {

        @Test
        @DisplayName("Health endpoint is publicly accessible")
        void healthEndpointNoAuthRequired() throws Exception {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Info endpoint requires authentication")
        void infoEndpointRequiresAuth() throws Exception {
            mockMvc.perform(get("/actuator/info"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Prometheus scrape endpoint requires authentication")
        void prometheusEndpointRequiresAuth() throws Exception {
            mockMvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Metrics endpoint requires authentication")
        void metricsEndpointRequiresAuth() throws Exception {
            mockMvc.perform(get("/actuator/metrics"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Sensitive actuator endpoint requires authentication")
        void loggersEndpointRequiresAuth() throws Exception {
            mockMvc.perform(get("/actuator/loggers"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
