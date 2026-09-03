package org.itech.ahb.connection;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService.Mode;
import org.itech.ahb.lib.astm.interpretation.ASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet;
import org.itech.ahb.lib.astm.servlet.ASTMServlet.ASTMVersion;
import org.itech.ahb.normalizer.ASTMBridgeAdapter;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.springframework.stereotype.Component;

/** Owns one dynamically bound ASTM server listener per active Bridge connection. */
@Component
public final class ManagedAstmConnectionListeners implements AstmConnectionListeners {

  private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(5);

  private final MessageNormalizer normalizer;
  private final ASTMInterpreterFactory interpreterFactory;
  private final Map<String, RunningListener> listeners = new HashMap<>();

  public ManagedAstmConnectionListeners(
    MessageNormalizer normalizer,
    ASTMInterpreterFactory interpreterFactory
  ) {
    this.normalizer = normalizer;
    this.interpreterFactory = interpreterFactory;
  }

  @Override
  public synchronized void start(
    String connectionId,
    String sourceBindingId,
    String analyzerId,
    int port,
    String lowerLayerVersion
  ) {
    RunningListener current = listeners.get(connectionId);
    if (current != null && current.matches(sourceBindingId, analyzerId, port, lowerLayerVersion)) {
      return;
    }
    stop(connectionId);

    ASTMHandlerService handlers = new ASTMHandlerService(
      java.util.List.of(new ASTMBridgeAdapter(normalizer, sourceBindingId)),
      Mode.FIRST
    );
    ASTMServlet servlet = new ASTMServlet(
      handlers,
      interpreterFactory,
      port,
      version(lowerLayerVersion)
    );
    Thread thread = Thread.ofPlatform()
      .name("astm-connection-" + connectionId)
      .start(servlet::listen);
    try {
      servlet.awaitStarted(STARTUP_TIMEOUT);
    } catch (RuntimeException exception) {
      servlet.stop();
      join(thread);
      throw new AnalyzerConnectionException(
        "Cannot activate ASTM listener for Bridge connection " + connectionId,
        exception
      );
    }
    listeners.put(
      connectionId,
      new RunningListener(sourceBindingId, analyzerId, port, lowerLayerVersion, servlet, thread)
    );
  }

  @Override
  public synchronized void stop(String connectionId) {
    RunningListener current = listeners.remove(connectionId);
    if (current == null) {
      return;
    }
    current.servlet().stop();
    join(current.thread());
  }

  synchronized boolean isRunning(String connectionId) {
    RunningListener listener = listeners.get(connectionId);
    return listener != null && listener.servlet().isRunning() && listener.thread().isAlive();
  }

  @PreDestroy
  public synchronized void stopAll() {
    for (String connectionId : java.util.List.copyOf(listeners.keySet())) {
      stop(connectionId);
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

  private record RunningListener(
    String sourceBindingId,
    String analyzerId,
    int port,
    String lowerLayerVersion,
    ASTMServlet servlet,
    Thread thread
  ) {

    private boolean matches(
      String expectedSourceBindingId,
      String expectedAnalyzerId,
      int expectedPort,
      String expectedLowerLayerVersion
    ) {
      return sourceBindingId.equals(expectedSourceBindingId) &&
      analyzerId.equals(expectedAnalyzerId) &&
      port == expectedPort &&
      lowerLayerVersion.equals(expectedLowerLayerVersion) &&
      servlet.isRunning() &&
      thread.isAlive();
    }
  }
}
