package org.itech.ahb.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.StringReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ThreadUtilTest {

  @Test
  @DisplayName("reads characters in order")
  void readsCharacters() throws Exception {
    BufferedReader reader = new BufferedReader(new StringReader("H|"));

    assertEquals('H', ThreadUtil.readCharWithInterruptCheck(reader));
    assertEquals('|', ThreadUtil.readCharWithInterruptCheck(reader));
  }

  @Test
  @DisplayName("end of stream raises EOFException rather than returning 0xFFFF as data")
  void endOfStreamThrows() throws Exception {
    BufferedReader reader = new BufferedReader(new StringReader(""));

    EOFException thrown = assertThrows(EOFException.class, () -> ThreadUtil.readCharWithInterruptCheck(reader));

    assertEquals("the astm sender closed the connection", thrown.getMessage());
  }

  @Test
  @DisplayName("a real 0xFFFF in the stream is still returned as data")
  void genuineFfffIsNotConfusedWithEndOfStream() throws Exception {
    BufferedReader reader = new BufferedReader(new StringReader("￿"));

    assertEquals('￿', ThreadUtil.readCharWithInterruptCheck(reader));
    assertThrows(EOFException.class, () -> ThreadUtil.readCharWithInterruptCheck(reader));
  }
}
