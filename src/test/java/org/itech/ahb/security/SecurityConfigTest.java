package org.itech.ahb.security;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Verifies that the /input endpoint requires HTTP Basic authentication
 * while actuator health/info endpoints remain publicly accessible.
 * </p>
 */
@SpringBootTest(properties = {
    "bridge.security.enabled=true",
    "bridge.security.username=testuser",
    "bridge.security.password=testpass",
    "org.itech.ahb.mllp.enabled=false",
    "org.itech.ahb.serial.enabled=false",
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
    @DisplayName("/api/analyzers management authentication")
    class AnalyzerProbeApiTests {

        @Test
        @DisplayName("Unauthenticated analyzer registration sync returns 401")
        void unauthenticatedAnalyzerRegistrationSyncReturns401() throws Exception {
            mockMvc.perform(put("/api/analyzers/sync")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Unauthenticated analyzer probe returns 401")
        void unauthenticatedAnalyzerProbeReturns401() throws Exception {
            mockMvc.perform(post("/api/analyzers/77/probe"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Authenticated analyzer probe reaches the controller")
        void authenticatedAnalyzerProbeReachesController() throws Exception {
            mockMvc.perform(post("/api/analyzers/missing/probe")
                    .with(httpBasic("testuser", "testpass")))
                    .andExpect(status().isNotFound());
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
        @DisplayName("Info endpoint does not require authentication")
        void infoEndpointNoAuthRequired() throws Exception {
            // Info may return 404 (no info contributors) or 200, but never 401/403
            int status = mockMvc.perform(get("/actuator/info"))
                    .andReturn().getResponse().getStatus();
            assertTrue(status != 401 && status != 403,
                    "Info endpoint should not require auth, but got " + status);
        }

        @Test
        @DisplayName("Prometheus scrape endpoint is publicly accessible for monitoring")
        void prometheusEndpointNoAuthRequired() throws Exception {
            int status = mockMvc.perform(get("/actuator/prometheus"))
                    .andReturn().getResponse().getStatus();
            assertTrue(status != 401 && status != 403,
                    "Prometheus endpoint should not require auth, but got " + status);
        }

        @Test
        @DisplayName("Metrics endpoint is publicly accessible for monitoring")
        void metricsEndpointNoAuthRequired() throws Exception {
            int status = mockMvc.perform(get("/actuator/metrics"))
                    .andReturn().getResponse().getStatus();
            assertTrue(status != 401 && status != 403,
                    "Metrics endpoint should not require auth, but got " + status);
        }

        @Test
        @DisplayName("Sensitive actuator endpoint requires authentication")
        void loggersEndpointRequiresAuth() throws Exception {
            mockMvc.perform(get("/actuator/loggers"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
