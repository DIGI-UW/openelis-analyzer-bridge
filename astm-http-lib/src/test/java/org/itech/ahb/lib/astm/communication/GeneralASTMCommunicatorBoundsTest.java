package org.itech.ahb.lib.astm.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet.ASTMVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * An ASTM listener accepts any TCP peer. Whatever it sends, a receipt ends in bounded time and
 * memory, and ends when the peer goes away.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class GeneralASTMCommunicatorBoundsTest {

  private static final byte STX = 0x02;
  private static final byte ETB = 0x17;
  private static final byte ENQ = 0x05;
  private static final byte ACK = 0x06;
  private static final byte CR = 0x0D;
  private static final byte LF = 0x0A;

  private ExecutorService executor;

  @AfterEach
  void tearDown() {
    if (executor != null) executor.shutdownNow();
  }

  private Future<ASTMMessage> receive(Socket serverSide, Duration frameDeadline, Duration messageDeadline)
    throws IOException {
    GeneralASTMCommunicator communicator = new GeneralASTMCommunicator(
      new DefaultASTMInterpreterFactory(),
      serverSide,
      ASTMVersion.LIS01_A
    );
    communicator.setReceiveDeadlines(frameDeadline, messageDeadline);
    executor = Executors.newSingleThreadExecutor();
    return executor.submit(() -> communicator.receiveProtocol(false));
  }

  private static byte[] frame(int number, String text) {
    char numberChar = Character.forDigit(number, 10);
    int checksum = numberChar;
    for (byte b : text.getBytes(StandardCharsets.ISO_8859_1)) checksum += b & 0xFF;
    checksum = (checksum + ETB) % 256;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(STX);
    out.write(numberChar);
    out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
    out.write(ETB);
    out.writeBytes(String.format("%02X", checksum).getBytes(StandardCharsets.US_ASCII));
    out.write(CR);
    out.write(LF);
    return out.toByteArray();
  }

  private interface Peer {
    void talk(OutputStream out, InputStream in) throws Exception;
  }

  /** Runs the peer against a receiver and returns how the receipt ended. */
  private Throwable failureOf(Duration frameDeadline, Duration messageDeadline, Peer peer) throws Exception {
    try (
      ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
      Socket serverSide = server.accept()
    ) {
      Future<ASTMMessage> receipt = receive(serverSide, frameDeadline, messageDeadline);
      // The peer runs on its own thread: once the receiver stops reading, a peer still writing would
      // block until the connection closes, which happens when this method returns.
      Thread sender = Thread.ofVirtual().start(() -> {
        try {
          peer.talk(client.getOutputStream(), client.getInputStream());
        } catch (Exception closedByReceiver) {
          // The receiver may end the connection while the peer is still talking.
        }
      });
      try {
        ExecutionException ended = assertThrows(ExecutionException.class, () -> receipt.get(10, TimeUnit.SECONDS));
        return ended.getCause();
      } finally {
        sender.interrupt();
      }
    }
  }

  @Test
  void aNonCompliantSenderThatDisconnectsEndsTheReceipt() throws Exception {
    failureOf(Duration.ofSeconds(30), Duration.ofSeconds(60), (out, in) -> {
      out.write("H|\\^&|||Analyzer\r".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      out.close();
    });
  }

  @Test
  void aNonCompliantRecordWithoutAnEndIsRefusedAtTheSizeLimit() throws Exception {
    byte[] filler = new byte[16 * 1024];
    Arrays.fill(filler, (byte) 'A');
    failureOf(Duration.ofSeconds(30), Duration.ofSeconds(60), (out, in) -> {
      out.write('H');
      for (int i = 0; i < 64; i++) out.write(filler);
      out.flush();
    });
  }

  @Test
  void aNonCompliantMessageStillIncompleteAtItsDeadlineEnds() throws Exception {
    failureOf(Duration.ofSeconds(30), Duration.ofSeconds(1), (out, in) -> {
      out.write("H|\\^&\r".getBytes(StandardCharsets.US_ASCII));
      for (int i = 0; i < 12; i++) {
        out.write("P|1\r".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        Thread.sleep(250);
      }
    });
  }

  @Test
  void aCompliantFrameLargerThanTheProtocolAllowsEndsTheReceipt() throws Exception {
    byte[] filler = new byte[16 * 1024];
    Arrays.fill(filler, (byte) 'A');
    failureOf(Duration.ofSeconds(30), Duration.ofSeconds(60), (out, in) -> {
      out.write(ENQ);
      out.flush();
      assertEquals(ACK, in.read());
      out.write(STX);
      out.write('1');
      for (int i = 0; i < 64; i++) out.write(filler);
      out.flush();
    });
  }

  @Test
  void aCompliantMessageWithTooManyFramesEndsTheReceipt() throws Exception {
    failureOf(Duration.ofSeconds(30), Duration.ofSeconds(60), (out, in) -> {
      out.write(ENQ);
      out.flush();
      assertEquals(ACK, in.read());
      for (int i = 1; i <= GeneralASTMCommunicator.MAX_FRAMES_PER_MESSAGE + 1; i++) {
        out.write(frame(i % 8, "P|" + i));
        out.flush();
        if (in.read() != ACK) return;
      }
    });
  }

  @Test
  void aCompliantFrameThatTricklesInEndsAtItsDeadline() throws Exception {
    failureOf(Duration.ofSeconds(1), Duration.ofSeconds(60), (out, in) -> {
      out.write(ENQ);
      out.flush();
      assertEquals(ACK, in.read());
      out.write(STX);
      out.write('1');
      for (int i = 0; i < 12; i++) {
        out.write('A');
        out.flush();
        Thread.sleep(250);
      }
    });
  }
}
