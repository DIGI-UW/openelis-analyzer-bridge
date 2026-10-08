package org.itech.ahb.mllp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The MLLP port accepts unauthenticated peers; none may hold unbounded memory or threads. */
class HapiMLLPLimitsTest {

  private static final byte VT = 0x0B;
  private static final byte FS = 0x1C;
  private static final byte CR = 0x0D;
  private static final String MESSAGE =
    "MSH|^~\\&|TestApp|Lab|OE|Lab|20260205120000||ORU^R01|M1|P|2.5.1\rPID|1||P1\r";

  private HapiMLLPListener listener;

  @AfterEach
  void stop() {
    if (listener != null) listener.stop();
  }

  private int start(MllpLimits limits) throws IOException {
    int port;
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    listener = new HapiMLLPListener(port, "connection:limits", envelope -> true, limits);
    listener.start();
    return port;
  }

  private static String exchange(int port) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      OutputStream out = socket.getOutputStream();
      out.write(VT);
      out.write(MESSAGE.getBytes(StandardCharsets.UTF_8));
      out.write(FS);
      out.write(CR);
      out.flush();
      return readFrame(socket.getInputStream());
    }
  }

  private static String readFrame(InputStream in) throws IOException {
    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1 && b != FS) {
      if (b != VT) frame.write(b);
    }
    return frame.toString(StandardCharsets.UTF_8);
  }

  /** True once the server has closed the connection, as seen by a read that ends or resets. */
  private static boolean closedByServer(Socket socket, Duration within) throws IOException {
    socket.setSoTimeout((int) within.toMillis());
    try {
      return socket.getInputStream().read() == -1;
    } catch (SocketTimeoutException stillOpen) {
      return false;
    } catch (IOException reset) {
      return true;
    }
  }

  @Test
  @Timeout(30)
  void anUnterminatedFrameIsCutOffAtTheMessageLimit() throws Exception {
    int port = start(new MllpLimits(8, 64 * 1024, Duration.ofSeconds(30)));
    try (Socket socket = new Socket("127.0.0.1", port)) {
      OutputStream out = socket.getOutputStream();
      byte[] filler = new byte[16 * 1024];
      Arrays.fill(filler, (byte) 'A');
      out.write(VT);
      try {
        for (int i = 0; i < 64; i++) out.write(filler);
        out.flush();
      } catch (IOException resetWhileWriting) {
        // The server closed the connection mid-stream.
      }
      assertTrue(closedByServer(socket, Duration.ofSeconds(10)), "the oversized frame must end the connection");
    }
    assertTrue(exchange(port).contains("MSA|AA|"), "other senders are unaffected");
  }

  @Test
  @Timeout(30)
  void connectionsBeyondTheLimitAreRefused() throws Exception {
    int port = start(new MllpLimits(2, 1024 * 1024, Duration.ofSeconds(30)));
    try (Socket first = new Socket("127.0.0.1", port); Socket second = new Socket("127.0.0.1", port)) {
      Thread.sleep(300);
      try (Socket third = new Socket("127.0.0.1", port)) {
        assertTrue(closedByServer(third, Duration.ofSeconds(5)), "a third concurrent connection is refused");
      }
      assertTrue(!closedByServer(first, Duration.ofMillis(300)), "admitted connections stay open");
    }
    Thread.sleep(500);
    assertTrue(exchange(port).contains("MSA|AA|"), "a closed connection frees its place");
  }

  @Test
  @Timeout(30)
  void aMessageThatTricklesInIsCutOffAtTheDeadline() throws Exception {
    int port = start(new MllpLimits(8, 1024 * 1024, Duration.ofSeconds(1)));
    try (Socket socket = new Socket("127.0.0.1", port)) {
      OutputStream out = socket.getOutputStream();
      out.write(VT);
      out.write("MSH|".getBytes(StandardCharsets.UTF_8));
      out.flush();
      boolean closed = false;
      for (int i = 0; i < 20 && !closed; i++) {
        Thread.sleep(250);
        try {
          out.write('A');
          out.flush();
        } catch (IOException reset) {
          closed = true;
        }
        closed = closed || closedByServer(socket, Duration.ofMillis(50));
      }
      assertTrue(closed, "a message still incomplete after its deadline must end the connection");
    }
    assertEquals(true, exchange(port).contains("MSA|AA|"));
  }

  @Test
  @Timeout(30)
  void aMessageThatStallsAfterItsStartIsCutOffAtTheDeadline() throws Exception {
    int port = start(new MllpLimits(8, 1024 * 1024, Duration.ofSeconds(1)));
    try (Socket socket = new Socket("127.0.0.1", port)) {
      OutputStream out = socket.getOutputStream();
      out.write(VT);
      out.write("MSH|".getBytes(StandardCharsets.UTF_8));
      out.flush();
      assertTrue(closedByServer(socket, Duration.ofSeconds(6)), "a silent sender must not keep its connection");
    }
  }
}
