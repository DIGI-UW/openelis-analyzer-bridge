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
 * Verifies that the /input endpoint and the management APIs require HTTP Basic
 * authentication while the actuator health status remains publicly accessible.
 * </p>
 */
@SpringBootTest(properties = {
    "bridge.security.enabled=true",
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
    @DisplayName("/input endpoint authentication")
    class InputEndpointTests {

        @Test
        @DisplayName("Unauthenticated POST to /input returns 401")
        void unauthenticatedInputReturns401() throws Exception {
            mockMvc.perform(post("/input")
                    .content(SAMPLE_ASTM)
                    .contentType(MediaType.TEXT_PLAIN))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Wrong credentials POST to /input returns 401")
        void wrongCredentialsReturns401() throws Exception {
            mockMvc.perform(post("/input")
                    .with(httpBasic("wrong", "credentials"))
                    .content(SAMPLE_ASTM)
                    .contentType(MediaType.TEXT_PLAIN))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Authenticated POST to /input succeeds")
        void authenticatedInputSucceeds() throws Exception {
            when(mockNormalizer.process(any(MessageEnvelope.class))).thenReturn(true);

            mockMvc.perform(post("/input")
                    .with(httpBasic("testuser", "testpass"))
                    .content(SAMPLE_ASTM)
                    .contentType("application/x-astm"))
                    .andExpect(status().isOk());
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
