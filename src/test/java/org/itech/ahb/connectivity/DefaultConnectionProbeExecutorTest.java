package org.itech.ahb.connectivity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultConnectionProbeExecutorTest {

  private static final byte ENQ = 0x05;
  private static final byte ACK = 0x06;
  private static final byte VT = 0x0b;
  private static final byte FS = 0x1c;
  private static final byte CR = 0x0d;

  private final DefaultConnectionProbeExecutor executor =
    new DefaultConnectionProbeExecutor();

  @Test
  void listenerProbeReportsAnAvailableBridgePort() throws IOException {
    int availablePort;
    try (ServerSocket server = new ServerSocket(0)) {
      availablePort = server.getLocalPort();
    }

    ProbeCheck check = executor.probeListener(availablePort);

    assertThat(check.kind()).isEqualTo("LISTENER");
    assertThat(check.status()).isEqualTo("PASSED");
    assertThat(check.code()).isEqualTo("listener.ready");
    assertThat(check.args()).containsEntry("port", availablePort);
  }

  @Test
  void listenerProbeRejectsAPortOwnedByAnotherProcess() throws IOException {
    try (ServerSocket server = new ServerSocket(0)) {
      ProbeCheck check = executor.probeListener(server.getLocalPort());

      assertThat(check.kind()).isEqualTo("LISTENER");
      assertThat(check.status()).isEqualTo("FAILED");
      assertThat(check.code()).isEqualTo("listener.port.in.use");
      assertThat(check.args()).containsEntry("port", server.getLocalPort());
    }
  }

  @Test
  void astmProbeRequiresTheProtocolAck() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      AtomicInteger terminator = new AtomicInteger(-1);
      Thread peer = astmPeer(server, terminator);

      ProbeCheck check = executor.probeRemote(
        "ASTM",
        "127.0.0.1",
        server.getLocalPort(),
        1000
      );

      assertThat(check.kind()).isEqualTo("REMOTE_PROTOCOL");
      assertThat(check.status()).isEqualTo("PASSED");
      assertThat(check.code()).isEqualTo("remote.astm.ready");
      peer.join(1000);
      assertThat(terminator.get()).isEqualTo(0x04);
    }
  }

  @Test
  void hl7ProbeRequiresAnMllpFramedResponse() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      Thread peer = mllpPeer(server);

      ProbeCheck check = executor.probeRemote(
        "HL7",
        "127.0.0.1",
        server.getLocalPort(),
        1000
      );

      assertThat(check.kind()).isEqualTo("REMOTE_PROTOCOL");
      assertThat(check.status()).isEqualTo("PASSED");
      assertThat(check.code()).isEqualTo("remote.hl7.ready");
      peer.join(1000);
    }
  }

  @Test
  void refusedRemoteConnectionReturnsStableFailureEvidence() throws IOException {
    int closedPort;
    try (ServerSocket server = new ServerSocket(0)) {
      closedPort = server.getLocalPort();
    }

    ProbeCheck check = executor.probeRemote(
      "ASTM",
      "127.0.0.1",
      closedPort,
      200
    );

    assertThat(check.status()).isEqualTo("FAILED");
    assertThat(check.code()).isEqualTo("remote.refused");
  }

  @Test
  void fileAndSerialProbesUseOnlyTheirRegisteredPaths(@TempDir Path tempDir)
    throws IOException {
    Path device = Files.createFile(tempDir.resolve("ttyUSB0"));

    ProbeCheck directory = executor.probeDirectory(tempDir.toString());
    ProbeCheck serial = executor.probeSerialDevice(device.toString());

    assertThat(directory.status()).isEqualTo("PASSED");
    assertThat(directory.code()).isEqualTo("directory.ready");
    assertThat(directory.args()).containsEntry("path", tempDir.toString());
    assertThat(serial.status()).isEqualTo("PASSED");
    assertThat(serial.code()).isEqualTo("serial.ready");
    assertThat(serial.args()).containsEntry("path", device.toString());
  }

  @Test
  void httpProbeReportsTheConfiguredEndpoint() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/health", exchange -> {
      byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream response = exchange.getResponseBody()) {
        response.write(body);
      }
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/health";

      ProbeCheck check = executor.probeHttpEndpoint(url, 1000);

      assertThat(check.status()).isEqualTo("PASSED");
      assertThat(check.code()).isEqualTo("http.ready");
      assertThat(check.args()).containsEntry("url", url);
    } finally {
      server.stop(0);
    }
  }

  private static Thread astmPeer(ServerSocket server, AtomicInteger terminator) {
    Thread peer = new Thread(() -> {
      try (Socket client = server.accept()) {
        if (client.getInputStream().read() == ENQ) {
          client.getOutputStream().write(ACK);
          client.getOutputStream().flush();
          terminator.set(client.getInputStream().read());
        }
      } catch (IOException exception) {
        throw new IllegalStateException(exception);
      }
    });
    peer.start();
    return peer;
  }

  private static Thread mllpPeer(ServerSocket server) {
    Thread peer = new Thread(() -> {
      try (Socket client = server.accept()) {
        InputStream input = client.getInputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
          if (previous == FS && current == CR) {
            break;
          }
          previous = current;
        }
        OutputStream output = client.getOutputStream();
        output.write(VT);
        output.write("MSH|^~\\&|MOCK||||||ACK|1|P|2.3.1\rMSA|AA|PING\r".getBytes(StandardCharsets.UTF_8));
        output.write(FS);
        output.write(CR);
        output.flush();
      } catch (IOException exception) {
        throw new IllegalStateException(exception);
      }
    });
    peer.start();
    return peer;
  }
}
