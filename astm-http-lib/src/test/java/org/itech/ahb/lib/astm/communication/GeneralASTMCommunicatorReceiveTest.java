package org.itech.ahb.lib.astm.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet.ASTMVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Drives the LIS01-A receive protocol over a loopback socket, replaying the transmission a Cepheid
 * GeneXpert at a French-language site actually sends.
 */
@Timeout(value = 25, unit = TimeUnit.SECONDS)
class GeneralASTMCommunicatorReceiveTest {

  private static final byte STX = 0x02;
  private static final byte ETX = 0x03;
  private static final byte EOT = 0x04;
  private static final byte ENQ = 0x05;
  private static final byte ACK = 0x06;
  private static final byte NAK = 0x15;
  private static final byte ETB = 0x17;
  private static final byte CR = 0x0D;
  private static final byte LF = 0x0A;

  private static final String HEADER_FRAME_TEXT =
    "H|@^\\|GXM-28780012200||110001949 CHUJRB ANTANANARIVO^GeneXpert^6.2|||||Genexpert||P|1394-97|20260811112013" +
    "\rP|1|||116 RELANCE JL 2026|^^^^|";

  private static final String RESULT_FRAME_TEXT =
    "\rR|1|^^^EV^Xpert HIV-1 Viral Load XC^3^^|NON DÉTECTÉ^|copies/mL|40,00 to 10000000,00|A||F||Genexpert" + "\rL|1|N";

  private ExecutorService executor;

  @AfterEach
  void tearDown() {
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  /**
   * Builds one ASTM frame exactly as a sender would, with the checksum summed over the payload bytes
   * actually placed on the wire — which is what the receiver has to reproduce.
   */
  private static byte[] buildFrame(int frameNumber, String text, byte terminator, Charset payloadCharset) {
    byte[] textBytes = text.getBytes(payloadCharset);
    char frameNumberChar = Character.forDigit(frameNumber, 10);

    int checksum = frameNumberChar & 0xFF;
    for (byte textByte : textBytes) {
      checksum += textByte & 0xFF;
    }
    checksum += terminator & 0xFF;
    checksum %= 256;

    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    frame.write(STX);
    frame.write((byte) frameNumberChar);
    frame.write(textBytes, 0, textBytes.length);
    frame.write(terminator);
    for (byte checksumByte : String.format("%02X", checksum).getBytes(StandardCharsets.US_ASCII)) {
      frame.write(checksumByte);
    }
    frame.write(CR);
    frame.write(LF);
    return frame.toByteArray();
  }

  private Future<ASTMMessage> startReceiver(Socket serverSide) throws IOException {
    GeneralASTMCommunicator communicator = new GeneralASTMCommunicator(
      new DefaultASTMInterpreterFactory(),
      serverSide,
      ASTMVersion.LIS01_A
    );
    executor = Executors.newSingleThreadExecutor();
    return executor.submit(() -> communicator.receiveProtocol(false));
  }

  private static byte readResponse(InputStream in) throws IOException {
    int response = in.read();
    assertFalse(response == -1, "receiver closed the connection instead of responding");
    return (byte) response;
  }

  @Test
  @DisplayName("a Latin-1 accented result is accepted, not rejected as a bad checksum")
  void acceptsLatin1AccentedResult() throws Exception {
    try (
      ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
      Socket serverSide = server.accept()
    ) {
      Future<ASTMMessage> received = startReceiver(serverSide);
      OutputStream out = client.getOutputStream();
      InputStream in = client.getInputStream();

      out.write(ENQ);
      out.flush();
      assertEquals(ACK, readResponse(in), "establishment should be acknowledged");

      out.write(buildFrame(1, HEADER_FRAME_TEXT, ETB, StandardCharsets.ISO_8859_1));
      out.flush();
      assertEquals(ACK, readResponse(in), "header frame should be acknowledged");

      out.write(buildFrame(2, RESULT_FRAME_TEXT, ETX, StandardCharsets.ISO_8859_1));
      out.flush();
      assertEquals(ACK, readResponse(in), "accented result frame must not be NAKed for a checksum mismatch");

      out.write(EOT);
      out.flush();

      ASTMMessage message = received.get(20, TimeUnit.SECONDS);
      String text = message.getMessage();

      assertTrue(text.contains("NON DÉTECTÉ"), "accented result text must survive intact, was: " + text);
      assertFalse(text.contains("�"), "no replacement characters should appear");
      assertTrue(text.startsWith("H|@^\\|GXM-28780012200||"), "header must be first");
      assertTrue(text.endsWith("L|1|N"), "terminator record must be last");
    }
  }

  @Test
  @DisplayName("a UTF-8 analyzer's accented result is decoded rather than turned into mojibake")
  void acceptsUtf8AccentedResult() throws Exception {
    try (
      ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
      Socket serverSide = server.accept()
    ) {
      Future<ASTMMessage> received = startReceiver(serverSide);
      OutputStream out = client.getOutputStream();
      InputStream in = client.getInputStream();

      out.write(ENQ);
      out.flush();
      assertEquals(ACK, readResponse(in));

      out.write(buildFrame(1, HEADER_FRAME_TEXT + RESULT_FRAME_TEXT, ETX, StandardCharsets.UTF_8));
      out.flush();
      assertEquals(ACK, readResponse(in), "UTF-8 frame must not be NAKed for a checksum mismatch");

      out.write(EOT);
      out.flush();

      String text = received.get(20, TimeUnit.SECONDS).getMessage();

      assertTrue(text.contains("NON DÉTECTÉ"), "UTF-8 payload must be decoded, was: " + text);
      assertFalse(text.contains("Ã"), "UTF-8 payload must not be left as mojibake, was: " + text);
    }
  }

  @Test
  @DisplayName("a transmission the sender aborts before the final frame still yields the received records")
  void recoversAbortedTransmission() throws Exception {
    try (
      ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
      Socket serverSide = server.accept()
    ) {
      Future<ASTMMessage> received = startReceiver(serverSide);
      OutputStream out = client.getOutputStream();
      InputStream in = client.getInputStream();

      out.write(ENQ);
      out.flush();
      assertEquals(ACK, readResponse(in));

      out.write(buildFrame(1, HEADER_FRAME_TEXT, ETB, StandardCharsets.ISO_8859_1));
      out.flush();
      assertEquals(ACK, readResponse(in));

      // Sender gives up here rather than sending an ETX-terminated final frame.
      out.write(EOT);
      out.flush();

      String text = received.get(20, TimeUnit.SECONDS).getMessage();

      assertFalse(text.isEmpty(), "buffered records must not be silently discarded");
      assertTrue(text.startsWith("H|@^\\|GXM-28780012200||"), "was: " + text);
    }
  }

  @Test
  @DisplayName("a corrupted frame is still rejected, so the checksum check has not been weakened")
  void rejectsGenuinelyCorruptedFrame() throws Exception {
    try (
      ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
      Socket serverSide = server.accept()
    ) {
      startReceiver(serverSide);
      OutputStream out = client.getOutputStream();
      InputStream in = client.getInputStream();

      out.write(ENQ);
      out.flush();
      assertEquals(ACK, readResponse(in));

      byte[] frame = buildFrame(1, HEADER_FRAME_TEXT, ETB, StandardCharsets.ISO_8859_1);
      frame[frame.length - 3] = (byte) (frame[frame.length - 3] ^ 0x01); // corrupt the checksum digit
      out.write(frame);
      out.flush();

      assertEquals(NAK, readResponse(in), "a frame whose checksum does not match must still be NAKed");
    }
  }
}
