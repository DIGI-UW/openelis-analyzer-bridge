package org.itech.ahb.mllp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.junit.jupiter.api.Test;

class HapiConnectionLifecycleTest {

  @Test
  void activationFailsWhenItsOwnSocketCannotBind() throws Exception {
    try (ServerSocket occupied = new ServerSocket(0)) {
      HapiMLLPListener listener = new HapiMLLPListener(occupied.getLocalPort(), "connection:one", envelope -> true);
      try {
        assertThrows(IllegalStateException.class, listener::start);
        assertFalse(listener.isRunning());
        assertFalse(occupied.isClosed(), "failed activation must not close somebody else's listener");
      } finally {
        listener.stop();
      }
    }
  }

  @Test
  void routesByListenerOwnershipNotThePayloadSenderOrPeerIp() throws Exception {
    AtomicReference<MessageEnvelope> received = new AtomicReference<>();
    HapiMLLPListener listener = new HapiMLLPListener(freePort(), "connection:owned", envelope -> {
      received.set(envelope);
      return true;
    });
    try {
      listener.start();
      assertTrue(send(listener.getPort()).contains("MSA|AA|"));
      assertEquals("connection:owned", received.get().getSourceId());
      assertEquals("OTHER-ANALYZER", received.get().getProtocolAnalyzerHint());
    } finally {
      listener.stop();
    }
  }

  @Test
  void stopClosesAdmissionsAndWaitsForAlreadyRoutedWork() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    HapiMLLPListener listener = new HapiMLLPListener(freePort(), "connection:drain", envelope -> {
      entered.countDown();
      try {
        return release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    });
    var executor = Executors.newFixedThreadPool(2);
    try {
      listener.start();
      var sender = executor.submit(() -> send(listener.getPort()));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var shutdown = executor.submit(listener::stop);
      assertThrows(TimeoutException.class, () -> shutdown.get(200, TimeUnit.MILLISECONDS));
      assertThrows(IOException.class, () -> {
        try (Socket socket = new Socket("127.0.0.1", listener.getPort())) {}
      });
      release.countDown();
      shutdown.get(5, TimeUnit.SECONDS);
      sender.get(5, TimeUnit.SECONDS);
      assertFalse(listener.isRunning());
      assertDoesNotThrow(listener::stop);
      try (ServerSocket replacement = new ServerSocket(listener.getPort())) {
        assertTrue(replacement.isBound());
      }
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      listener.stop();
    }
  }

  static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  static String send(int port) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      String message =
        "MSH|^~\\&|OTHER-ANALYZER|OTHER-LAB|OE|LAB|20260909120000||ORU^R01|CONTROL-ID|P|2.5.1\r" +
        "PID|1||PATIENT\rOBR|1||ACCESSION|PANEL\rOBX|1|NM|T1||2|unit\r";
      socket.getOutputStream().write(("\u000b" + message + "\u001c\r").getBytes(StandardCharsets.UTF_8));
      var response = new StringBuilder();
      int next;
      while ((next = socket.getInputStream().read()) != -1 && next != 0x1c) response.append((char) next);
      return response.toString();
    }
  }
}
