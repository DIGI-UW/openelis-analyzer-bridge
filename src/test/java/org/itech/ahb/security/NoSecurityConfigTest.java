package org.itech.ahb.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for bridge security when disabled (NoSecurityConfig).
 * <p>
 * Verifies that when {@code bridge.security.enabled=false}, the /input endpoint
 * accepts unauthenticated requests.
 * </p>
 */
@SpringBootTest(properties = {
    "bridge.security.enabled=false",
    "org.itech.ahb.mllp.enabled=false",
    "bridge.file.enabled=false"
})
@AutoConfigureMockMvc
class NoSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessageNormalizer mockNormalizer;

    private static final String SAMPLE_ASTM = "H|\\^&|||HOST^1.0|||||||LIS2-A2|20260206\r"
            + "P|1||||Doe^John\rL|1|N";

    @Test
    @DisplayName("Unauthenticated POST to /input succeeds when security disabled")
    void unauthenticatedInputSucceedsWhenSecurityDisabled() throws Exception {
        when(mockNormalizer.process(any(MessageEnvelope.class))).thenReturn(true);

        mockMvc.perform(post("/input")
                .content(SAMPLE_ASTM)
                .contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk());
    }
}
