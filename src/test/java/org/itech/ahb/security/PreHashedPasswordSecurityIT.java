package org.itech.ahb.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
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
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies {@code bridge.security.password} in delegating-encoded form works for HTTP Basic.
 */
@SpringBootTest(properties = {
    "bridge.security.enabled=true",
    "bridge.security.username=testuser",
    "org.itech.ahb.mllp.enabled=false",
    "bridge.file.enabled=false",
})
@AutoConfigureMockMvc
class PreHashedPasswordSecurityIT {

    private static final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @DynamicPropertySource
    static void registerPreEncodedPassword(DynamicPropertyRegistry registry) {
        registry.add("bridge.security.password", () -> encoder.encode("from-secret-manager"));
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessageNormalizer mockNormalizer;

    @Test
    @DisplayName("HTTP Basic accepts plaintext that matches pre-encoded bridge.security.password")
    void inputWithMatchingPlaintextSucceeds() throws Exception {
        when(mockNormalizer.process(any(MessageEnvelope.class))).thenReturn(true);

        mockMvc.perform(post("/input")
                .with(httpBasic("testuser", "from-secret-manager"))
                .content("H|\\^&\r")
                .contentType("application/x-astm"))
                .andExpect(status().isOk());
    }
}
