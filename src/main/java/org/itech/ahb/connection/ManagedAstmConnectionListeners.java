package org.itech.ahb.connection;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService.Mode;
import org.itech.ahb.lib.astm.interpretation.ASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet;
import org.itech.ahb.lib.astm.servlet.ASTMServlet.ASTMVersion;
import org.itech.ahb.normalizer.ASTMBridgeAdapter;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.springframework.stereotype.Component;

/**
 * Sole owner of the ASTM server sockets: one listener per port, shared by every holder.
 *
 * <p>The configured boot listeners are held for the life of the application, so an analyzer can
 * reach them before any connection exists. A connection that declares a boot port joins that
 * listener; a connection that declares any other port gets a listener of its own, closed when its
 * last holder releases it. Any number of connections may share a listener: each message is
 * attributed by {@link AnalyzerRuntimeRegistry#resolve}.
 */
@Component
public final class ManagedAstmConnectionListeners implements AstmConnectionListeners {

  private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(5);

  private final MessageNormalizer normalizer;
  private final ASTMInterpreterFactory interpreterFactory;
  private final Map<Integer, SharedListener> listenersByPort = new HashMap<>();
  private final Map<String, Integer> portByConnection = new HashMap<>();

  public ManagedAstmConnectionListeners(
    MessageNormalizer normalizer,
    ASTMInterpreterFactory interpreterFactory
  ) {
    this.normalizer = normalizer;
    this.interpreterFactory = interpreterFactory;
  }

  /**
   * Registers a boot listener, held until shutdown, and returns its servlet for the caller to run.
   * The servlet is not started here: {@code ASTMServerRunnerTrigger} runs the boot servlets.
   */
  public synchronized ASTMServlet holdBootListener(int port, String lowerLayerVersion) {
    SharedListener listener = listenersByPort.get(port);
    if (listener == null) {
      listener = newListener(port, lowerLayerVersion);
      listenersByPort.put(port, listener);
    } else {
      listener.requireVersion(lowerLayerVersion);
    }
    listener.bootHeld = true;
    return listener.servlet;
  }

  @Override
  public synchronized void start(
    String connectionId,
    String analyzerId,
    int port,
    String lowerLayerVersion
  ) {
    Integer heldPort = portByConnection.get(connectionId);
    if (heldPort != null && heldPort != port) {
      stop(connectionId);
    }

    SharedListener listener = listenersByPort.get(port);
    boolean created = listener == null;
    if (created) {
      listener = newListener(port, lowerLayerVersion);
    } else {
      listener.requireVersion(lowerLayerVersion);
    }

    try {
      ensureListening(listener, connectionId);
    } catch (AnalyzerConnectionException exception) {
      if (created) {
        stopServlet(listener);
      }
      throw exception;
    }
    if (created) {
      listenersByPort.put(port, listener);
    }
    listener.connections.add(connectionId);
    portByConnection.put(connectionId, port);
  }

  @Override
  public synchronized void stop(String connectionId) {
    Integer port = portByConnection.remove(connectionId);
    if (port == null) {
      return;
    }
    SharedListener listener = listenersByPort.get(port);
    if (listener == null) {
      return;
    }
    listener.connections.remove(connectionId);
    if (listener.connections.isEmpty() && !listener.bootHeld) {
      listenersByPort.remove(port);
      stopServlet(listener);
    }
  }

  synchronized boolean isRunning(String connectionId) {
    Integer port = portByConnection.get(connectionId);
    return port != null && isListening(port);
  }

  @Override
  public synchronized boolean isListening(int port) {
    SharedListener listener = listenersByPort.get(port);
    return listener != null && listener.servlet.isRunning();
  }

  @PreDestroy
  public synchronized void stopAll() {
    for (SharedListener listener : List.copyOf(listenersByPort.values())) {
      stopServlet(listener);
    }
    listenersByPort.clear();
    portByConnection.clear();
  }

  private SharedListener newListener(int port, String lowerLayerVersion) {
    SharedListener listener = new SharedListener(port, lowerLayerVersion);
    ASTMHandlerService handlers = new ASTMHandlerService(
      // Unbound: every message carries its peer address and this listener's port, and the
      // registry resolves which of the connections on this port it belongs to.
      List.of(new ASTMBridgeAdapter(normalizer, port)),
      Mode.FIRST
    );
    listener.servlet = new ASTMServlet(handlers, interpreterFactory, port, version(lowerLayerVersion));
    return listener;
  }

  private void ensureListening(SharedListener listener, String connectionId) {
    if (listener.servlet.isRunning()) {
      return;
    }
    if (listener.thread == null || !listener.thread.isAlive()) {
      // A boot listener may not have been run yet (or ASTM boot listeners are disabled);
      // ASTMServlet.listen() ignores a second concurrent start, so this cannot double-bind.
      listener.thread = Thread.ofPlatform().name("astm-listener-" + listener.port).start(listener.servlet::listen);
    }
    try {
      listener.servlet.awaitStarted(STARTUP_TIMEOUT);
    } catch (RuntimeException exception) {
      throw new AnalyzerConnectionException(
        "Cannot activate ASTM listener on port " + listener.port + " for Bridge connection " + connectionId,
        exception
      );
    }
  }

  private static void stopServlet(SharedListener listener) {
    listener.servlet.stop();
    if (listener.thread != null) {
      join(listener.thread);
    }
  }

  private static ASTMVersion version(String lowerLayerVersion) {
    try {
      return ASTMVersion.valueOf(lowerLayerVersion);
    } catch (IllegalArgumentException exception) {
      throw new AnalyzerConnectionException(
        "Unsupported ASTM lower-layer version " + lowerLayerVersion,
        exception
      );
    }
  }

  private static void join(Thread thread) {
    try {
      thread.join(Duration.ofSeconds(2));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AnalyzerConnectionException("Interrupted while stopping ASTM listener", exception);
    }
    if (thread.isAlive()) {
      throw new AnalyzerConnectionException("ASTM listener did not stop cleanly");
    }
  }

  private static final class SharedListener {

    private final int port;
    private final String lowerLayerVersion;
    private final Set<String> connections = new LinkedHashSet<>();
    private ASTMServlet servlet;
    private Thread thread;
    private boolean bootHeld;

    private SharedListener(int port, String lowerLayerVersion) {
      this.port = port;
      this.lowerLayerVersion = lowerLayerVersion;
    }

    private void requireVersion(String requested) {
      if (!lowerLayerVersion.equals(requested)) {
        throw new AnalyzerConnectionException(
          "ASTM port " + port + " already listens for " + lowerLayerVersion + ", not " + requested
        );
      }
    }
  }
}
