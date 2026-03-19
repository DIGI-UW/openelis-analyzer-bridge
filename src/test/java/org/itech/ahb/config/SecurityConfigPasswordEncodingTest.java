package org.itech.ahb.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class SecurityConfigPasswordEncodingTest {

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Test
    void plaintextIsEncoded() {
        String stored = SecurityConfig.encodePasswordIfPlaintext("hello", passwordEncoder);
        assertTrue(stored.startsWith("{bcrypt}"));
        assertTrue(passwordEncoder.matches("hello", stored));
    }

    @Test
    void delegatingEncodedPasswordIsUnchanged() {
        String preEncoded = passwordEncoder.encode("secret");
        assertEquals(preEncoded, SecurityConfig.encodePasswordIfPlaintext(preEncoded, passwordEncoder));
    }
}
