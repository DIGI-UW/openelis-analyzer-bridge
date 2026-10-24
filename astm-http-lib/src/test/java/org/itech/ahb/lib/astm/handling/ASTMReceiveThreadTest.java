package org.itech.ahb.lib.astm.handling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService.Mode;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet;
import org.itech.ahb.lib.astm.servlet.ASTMServlet.ASTMVersion;
import org.itech.ahb.lib.common.handling.HandleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * An instrument such as a GeneXpert keeps one connection open and expects the reply to its host
 * query on it, in the other direction, before it sends anything else.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ASTMReceiveThreadTest {

  private static final byte STX = 0x02;
  private static final byte ETX = 0x03;
  private static final byte EOT = 0x04;
  private static final byte ENQ = 0x05;
  private static final byte ACK = 0x06;
  private static final byte CR = 0x0D;
  private static final byte LF = 0x0A;

  private static final String QUERY =
    "H|@^\\|q-1||SITE^GeneXpert^6.2|||||LIS||P|1394-97|20261020101500\rQ|1|^S-1||||||||||O@N\rL|1|N";
  private static final String RESULT = "H|@^\\|r-1||SITE^GeneXpert^6.2|||||LIS||P|1394-97|20261020101600\rL|1|N";
  private static final String REPLY = "H|@^\\|a-1||LIS|||||SITE^GeneXpert^6.2||P|1394-97|20261020101500\rL|1|I";

  private final List<String> handled = new CopyOnWriteArrayList<>();
  private ASTMServlet servlet;
  private Thread listener;

  @AfterEach
  void stop() throws InterruptedException {
    if (servlet != null) servlet.stop();
    if (listener != null) listener.join(Duration.ofSeconds(2));
  }

  @Test
  void answersAHostQueryOnItsConnectionAndKeepsReceivingOnIt() throws Exception {
    int port = start();
    try (Socket instrument = new Socket(InetAddress.getLoopbackAddress(), port)) {
      instrument.setSoTimeout(5000);
      InputStream in = instrument.getInputStream();
      OutputStream out = instrument.getOutputStream();

      send(in, out, QUERY);

      assertEquals(ENQ, in.read(), "the Bridge takes the line to answer");
      out.write(ACK);
      out.flush();
      String reply = receiveFrames(in, out);
      // Each record ends with a carriage return inside its frame, as the receiving instrument requires.
      assertTrue(reply.contains("20261020101500\r" + (char) ETX), reply);
      assertTrue(reply.contains("L|1|I\r" + (char) ETX), reply);

      send(in, out, RESULT);
    }

    awaitHandled(2);
    assertEquals(2, handled.size());
    assertTrue(handled.get(0).contains("Q|1|^S-1"), handled.get(0));
    assertTrue(handled.get(1).contains("|r-1|"), handled.get(1));
  }

  private int start() throws Exception {
    int port;
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    ASTMHandler handler = new ASTMHandler() {
      @Override
      public String getName() {
        return "query answerer";
      }

      @Override
      public ASTMHandlerResponse handle(ASTMMessage message, String sourceIp) {
        handled.add(message.getMessage());
        boolean query = message.getMessage().contains("\rQ|");
        return new ASTMHandlerResponse(query ? REPLY : "", HandleStatus.SUCCESS, query, this);
      }

      @Override
      public boolean matches(ASTMMessage message) {
        return true;
      }
    };
    servlet = new ASTMServlet(
      new ASTMHandlerService(List.of(handler), Mode.FIRST),
      new DefaultASTMInterpreterFactory(),
      port,
      ASTMVersion.LIS01_A
    );
    listener = Thread.ofPlatform().start(servlet::listen);
    servlet.awaitStarted(Duration.ofSeconds(2));
    return port;
  }

  private void awaitHandled(int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (handled.size() < count && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
  }

  /** Sends one message as a single frame, as the instrument does. */
  private static void send(InputStream in, OutputStream out, String message) throws IOException {
    out.write(ENQ);
    out.flush();
    assertEquals(ACK, in.read());
    byte[] body = ("1" + message + "\r").getBytes(StandardCharsets.ISO_8859_1);
    int checksum = ETX;
    for (byte b : body) checksum += b & 0xFF;
    out.write(STX);
    out.write(body);
    out.write(ETX);
    out.write(String.format("%02X", checksum % 256).getBytes(StandardCharsets.ISO_8859_1));
    out.write(CR);
    out.write(LF);
    out.flush();
    assertEquals(ACK, in.read());
    out.write(EOT);
    out.flush();
  }

  /** Acknowledges each frame until the sender ends its message with EOT. */
  private static String receiveFrames(InputStream in, OutputStream out) throws IOException {
    ByteArrayOutputStream frames = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1 && b != EOT) {
      frames.write(b);
      if (b == LF) {
        out.write(ACK);
        out.flush();
      }
    }
    assertEquals(EOT, b);
    return frames.toString(StandardCharsets.ISO_8859_1);
  }
}
