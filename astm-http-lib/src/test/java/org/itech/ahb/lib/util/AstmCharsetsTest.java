package org.itech.ahb.lib.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AstmCharsetsTest {

  @Test
  @DisplayName("transport charset round-trips every byte value losslessly")
  void transportIsByteTransparent() {
    byte[] allBytes = new byte[256];
    for (int i = 0; i < 256; i++) {
      allBytes[i] = (byte) i;
    }

    String decoded = new String(allBytes, AstmCharsets.TRANSPORT);

    assertEquals(256, decoded.length(), "each byte must decode to exactly one char");
    assertArrayEquals(allBytes, decoded.getBytes(AstmCharsets.TRANSPORT));
  }

  @Test
  @DisplayName("UTF-8 read of a Latin-1 accented byte is lossy, which is why transport is ISO-8859-1")
  void utf8ReadOfLatin1IsLossy() {
    byte[] wire = "NON DÉTECTÉ".getBytes(StandardCharsets.ISO_8859_1);

    String viaUtf8 = new String(wire, StandardCharsets.UTF_8);
    String viaTransport = new String(wire, AstmCharsets.TRANSPORT);

    assertTrue(viaUtf8.contains("�"), "UTF-8 decode replaces the undecodable byte");
    assertFalse(
      java.util.Arrays.equals(wire, viaUtf8.getBytes(StandardCharsets.UTF_8)),
      "the replacement char cannot be re-encoded to the original byte, so the checksum cannot match"
    );
    assertArrayEquals(wire, viaTransport.getBytes(AstmCharsets.TRANSPORT), "transport read is reversible");
  }

  @Test
  @DisplayName("Latin-1 payload is left as read, so accented text is correct")
  void decodePayloadKeepsLatin1() {
    String transportText = new String("NON DÉTECTÉ".getBytes(StandardCharsets.ISO_8859_1), AstmCharsets.TRANSPORT);

    assertEquals("NON DÉTECTÉ", AstmCharsets.decodePayload(transportText));
    assertFalse(AstmCharsets.isUtf8Payload(transportText));
  }

  @Test
  @DisplayName("UTF-8 payload is decoded, so a UTF-8 analyzer is not turned into mojibake")
  void decodePayloadDecodesUtf8() {
    String transportText = new String("NON DÉTECTÉ".getBytes(StandardCharsets.UTF_8), AstmCharsets.TRANSPORT);

    assertTrue(AstmCharsets.isUtf8Payload(transportText));
    assertEquals("NON DÉTECTÉ", AstmCharsets.decodePayload(transportText));
  }

  @Test
  @DisplayName("ASCII is unaffected either way")
  void decodePayloadLeavesAsciiAlone() {
    String ascii = "R|1|^^^EV^Xpert HIV-1 Viral Load XC^3^^|NOT DETECTED^|copies/mL|A||F";

    assertTrue(AstmCharsets.isUtf8Payload(ascii));
    assertEquals(ascii, AstmCharsets.decodePayload(ascii));
  }

  @Test
  @DisplayName("null and empty input are passed through")
  void decodePayloadHandlesEdgeCases() {
    assertNull(AstmCharsets.decodePayload(null));
    assertEquals("", AstmCharsets.decodePayload(""));
    assertTrue(AstmCharsets.isUtf8Payload(null));
  }
}
