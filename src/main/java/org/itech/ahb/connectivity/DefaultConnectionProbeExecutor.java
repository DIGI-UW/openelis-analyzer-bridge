package org.itech.ahb.connectivity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Performs protocol-aware connectivity checks from the Bridge network. */
@Component
public final class DefaultConnectionProbeExecutor
  implements ConnectionProbeExecutor {

  private static final int LISTENER_TIMEOUT_MS = 1000;
  private static final byte ENQ = 0x05;
  private static final byte ACK = 0x06;
  private static final byte VT = 0x0b;
  private static final byte FS = 0x1c;
  private static final byte CR = 0x0d;

  @Override
  public ProbeCheck probeListener(int port) {
    long started = System.nanoTime();
    Map<String, Object> args = Map.of("port", port);
    try (Socket socket = new Socket()) {
      socket.connect(
        new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
        LISTENER_TIMEOUT_MS
      );
      return check("LISTENER", "PASSED", "listener.ready", started, args);
    } catch (SocketTimeoutException exception) {
      return check("LISTENER", "TIMED_OUT", "listener.timeout", started, args);
    } catch (IOException exception) {
      return check("LISTENER", "FAILED", "listener.not.listening", started, args);
    }
  }

  @Override
  public ProbeCheck probeRemote(
    String protocol,
    String host,
    int port,
    int timeoutMs
  ) {
    long started = System.nanoTime();
    Map<String, Object> args = networkArgs(host, port);
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), timeoutMs);
      socket.setSoTimeout(timeoutMs);
      if ("ASTM".equals(protocol)) {
        return probeAstm(socket, started, args);
      }
      if ("HL7".equals(protocol)) {
        return probeMllp(socket, started, args);
      }
      return check(
        "REMOTE_PROTOCOL",
        "FAILED",
        "remote.protocol.unsupported",
        started,
        args
      );
    } catch (SocketTimeoutException exception) {
      return check(
        "REMOTE_PROTOCOL",
        "TIMED_OUT",
        "remote.timeout",
        started,
        args
      );
    } catch (ConnectException exception) {
      return check(
        "REMOTE_PROTOCOL",
        "FAILED",
        "remote.refused",
        started,
        args
      );
    } catch (UnknownHostException exception) {
      return check(
        "REMOTE_PROTOCOL",
        "FAILED",
        "remote.host.unknown",
        started,
        args
      );
    } catch (IOException exception) {
      return check(
        "REMOTE_PROTOCOL",
        "FAILED",
        "remote.connection.error",
        started,
        args
      );
    }
  }

  @Override
  public ProbeCheck probeDirectory(String path) {
    long started = System.nanoTime();
    Map<String, Object> args = Map.of("path", path);
    try {
      Path directory = Path.of(path);
      if (!Files.exists(directory)) {
        return check(
          "DIRECTORY",
          "FAILED",
          "directory.not.found",
          started,
          args
        );
      }
      if (!Files.isDirectory(directory)) {
        return check(
          "DIRECTORY",
          "FAILED",
          "directory.not.directory",
          started,
          args
        );
      }
      if (!Files.isReadable(directory)) {
        return check(
          "DIRECTORY",
          "FAILED",
          "directory.not.readable",
          started,
          args
        );
      }
      Map<String, Object> readyArgs = new LinkedHashMap<>(args);
      readyArgs.put("writable", Files.isWritable(directory));
      return check(
        "DIRECTORY",
        "PASSED",
        "directory.ready",
        started,
        readyArgs
      );
    } catch (InvalidPathException exception) {
      return check(
        "DIRECTORY",
        "FAILED",
        "directory.path.invalid",
        started,
        args
      );
    }
  }

  @Override
  public ProbeCheck probeSerialDevice(String path) {
    long started = System.nanoTime();
    Map<String, Object> args = Map.of("path", path);
    try {
      Path device = Path.of(path);
      if (!Files.exists(device)) {
        return check(
          "SERIAL_DEVICE",
          "FAILED",
          "serial.not.found",
          started,
          args
        );
      }
      if (!Files.isReadable(device)) {
        return check(
          "SERIAL_DEVICE",
          "FAILED",
          "serial.not.readable",
          started,
          args
        );
      }
      return check(
        "SERIAL_DEVICE",
        "PASSED",
        "serial.ready",
        started,
        args
      );
    } catch (InvalidPathException exception) {
      return check(
        "SERIAL_DEVICE",
        "FAILED",
        "serial.path.invalid",
        started,
        args
      );
    }
  }

  @Override
  public ProbeCheck probeHttpEndpoint(String baseUrl, int timeoutMs) {
    long started = System.nanoTime();
    Map<String, Object> args = Map.of("url", baseUrl);
    try {
      HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
        .timeout(Duration.ofMillis(timeoutMs))
        .GET()
        .build();
      HttpResponse<Void> response = client.send(
        request,
        HttpResponse.BodyHandlers.discarding()
      );
      Map<String, Object> responseArgs = new LinkedHashMap<>(args);
      responseArgs.put("status", response.statusCode());
      if (response.statusCode() < 500) {
        return check(
          "HTTP_ENDPOINT",
          "PASSED",
          "http.ready",
          started,
          responseArgs
        );
      }
      return check(
        "HTTP_ENDPOINT",
        "FAILED",
        "http.server.error",
        started,
        responseArgs
      );
    } catch (java.net.http.HttpTimeoutException exception) {
      return check(
        "HTTP_ENDPOINT",
        "TIMED_OUT",
        "http.timeout",
        started,
        args
      );
    } catch (IllegalArgumentException exception) {
      return check(
        "HTTP_ENDPOINT",
        "FAILED",
        "http.url.invalid",
        started,
        args
      );
    } catch (IOException exception) {
      return check(
        "HTTP_ENDPOINT",
        "FAILED",
        "http.connection.error",
        started,
        args
      );
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return check(
        "HTTP_ENDPOINT",
        "FAILED",
        "http.interrupted",
        started,
        args
      );
    }
  }

  private static ProbeCheck probeAstm(
    Socket socket,
    long started,
    Map<String, Object> args
  ) throws IOException {
    socket.getOutputStream().write(ENQ);
    socket.getOutputStream().flush();
    if (socket.getInputStream().read() == ACK) {
      return check(
        "REMOTE_PROTOCOL",
        "PASSED",
        "remote.astm.ready",
        started,
        args
      );
    }
    return check(
      "REMOTE_PROTOCOL",
      "FAILED",
      "remote.astm.response.invalid",
      started,
      args
    );
  }

  private static ProbeCheck probeMllp(
    Socket socket,
    long started,
    Map<String, Object> args
  ) throws IOException {
    byte[] message = "MSH|^~\\&|BRIDGE|PROBE|||||ACK|PING|P|2.3.1\r".getBytes(
      StandardCharsets.UTF_8
    );
    OutputStream output = socket.getOutputStream();
    output.write(VT);
    output.write(message);
    output.write(FS);
    output.write(CR);
    output.flush();

    InputStream input = socket.getInputStream();
    if (input.read() != VT) {
      return check(
        "REMOTE_PROTOCOL",
        "FAILED",
        "remote.hl7.response.invalid",
        started,
        args
      );
    }
    int previous = -1;
    int current;
    while ((current = input.read()) != -1) {
      if (previous == FS && current == CR) {
        return check(
          "REMOTE_PROTOCOL",
          "PASSED",
          "remote.hl7.ready",
          started,
          args
        );
      }
      previous = current;
    }
    return check(
      "REMOTE_PROTOCOL",
      "FAILED",
      "remote.hl7.response.invalid",
      started,
      args
    );
  }

  private static Map<String, Object> networkArgs(String host, int port) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("host", host);
    args.put("port", port);
    return args;
  }

  private static ProbeCheck check(
    String kind,
    String status,
    String code,
    long started,
    Map<String, Object> args
  ) {
    long responseTimeMs = Duration.ofNanos(
      System.nanoTime() - started
    ).toMillis();
    return new ProbeCheck(kind, status, code, responseTimeMs, args);
  }
}
