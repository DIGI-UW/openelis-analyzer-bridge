package org.itech.ahb.lib.util;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;

/**
 * Utility class for thread-related operations.
 */
public class ThreadUtil {

  /**
   * Reads a character from the given BufferedReader and checks for thread interruption.
   * Throws an InterruptedException if the thread is interrupted.
   *
   * <p>End of stream is reported as an {@link EOFException} rather than returned as a character.
   * Casting the {@code -1} that {@link BufferedReader#read()} returns at end of stream straight to
   * {@code char} yields {@code 0xFFFF}, which callers cannot distinguish from received data: a
   * closed connection was reported as an illegal ASTM control character, and a disconnect part way
   * through a frame left the frame-reading loop spinning on a terminator that could never arrive.
   *
   * @param reader the BufferedReader to read from.
   * @return the read character.
   * @throws EOFException if the stream has ended.
   * @throws IOException if an I/O error occurs.
   * @throws InterruptedException if the thread is interrupted.
   */
  public static char readCharWithInterruptCheck(BufferedReader reader) throws IOException, InterruptedException {
    int character = reader.read();
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
    if (character == -1) {
      throw new EOFException("the astm sender closed the connection");
    }
    return (char) character;
  }
}
