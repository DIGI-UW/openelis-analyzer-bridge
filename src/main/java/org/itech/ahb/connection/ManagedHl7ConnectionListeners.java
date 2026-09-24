package org.itech.ahb.connection;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.mllp.HapiMLLPListener;
import org.itech.ahb.mllp.MLLPConfig;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.itech.ahb.routing.MessageRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Sole owner of the MLLP server sockets: one listener per port, shared by every HL7 connection
 * that declares it.
 *
 * <p>When the HL7 runtime is enabled, the configured MLLP port is bound at boot and held for the
 * life of the application, so an analyzer can reach it before any connection exists. Each message
 * carries its peer address and listener port and is attributed by
 * {@link AnalyzerRuntimeRegistry#resolve}.
 */
@Component
@EnableConfigurationProperties(MLLPConfig.class)
@Slf4j
public final class ManagedHl7ConnectionListeners implements Hl7ConnectionListeners {

  private final MLLPConfig config;
  private final MessageRouter router;
  private final Map<Integer, SharedListener> listenersByPort = new HashMap<>();
  private final Map<String, Integer> portByConnection = new HashMap<>();
  private boolean stopping;

  @Autowired
  public ManagedHl7ConnectionListeners(MLLPConfig config, MessageNormalizer normalizer) {
    this(config, (MessageRouter) normalizer);
  }

  public ManagedHl7ConnectionListeners(MLLPConfig config, MessageRouter router) {
    this.config = config;
    this.router = router;
  }

  /** Binds the configured MLLP port at boot when the HL7 runtime is enabled. */
  @PostConstruct
  public synchronized void startBootListener() {
    if (!config.isEnabled() || config.getPort() <= 0) {
      return;
    }
    try {
      listenerFor(config.getPort()).bootHeld = true;
    } catch (AnalyzerConnectionException failure) {
      // An occupied port must not stop the bridge; saved connections on other ports still work.
      log.error("Cannot bind the MLLP listener on port {} at boot: {}", config.getPort(), failure.getMessage());
    }
  }

  @Override
  public synchronized void start(String connectionId, int port) {
    if (!config.isEnabled() || stopping) throw new AnalyzerConnectionException(
      "Saved HL7 listener runtime is disabled or stopping"
    );
    Integer heldPort = portByConnection.get(connectionId);
    if (heldPort != null && heldPort != port) {
      stop(connectionId);
    }
    SharedListener listener;
    try {
      listener = listenerFor(port);
    } catch (AnalyzerConnectionException failure) {
      throw new AnalyzerConnectionException(
        "Cannot activate HL7 listener for Bridge connection " + connectionId,
        failure.getCause() == null ? failure : failure.getCause()
      );
    }
    listener.connections.add(connectionId);
    portByConnection.put(connectionId, port);
  }

  @Override
  public synchronized void stop(String connectionId) {
    Integer port = portByConnection.remove(connectionId);
    if (port == null) return;
    SharedListener listener = listenersByPort.get(port);
    if (listener == null) return;
    listener.connections.remove(connectionId);
    if (listener.connections.isEmpty() && !listener.bootHeld) {
      listener.listener.stop();
      // Failed drains throw before this line, so the listener keeps ownership of live work.
      listenersByPort.remove(port);
    }
  }

  /** Whether each active connection's listener is running, by connection. */
  public synchronized Map<String, Boolean> runningConnections() {
    Map<String, Boolean> state = new HashMap<>();
    portByConnection.forEach((id, port) -> {
      SharedListener listener = listenersByPort.get(port);
      state.put(id, listener != null && listener.listener.isRunning());
    });
    return Map.copyOf(state);
  }

  @Override
  public synchronized boolean isListening(int port) {
    SharedListener listener = listenersByPort.get(port);
    return listener != null && listener.listener.isRunning();
  }

  public boolean isEnabled() {
    return config.isEnabled();
  }

  @PreDestroy
  public synchronized void stopAll() {
    stopping = true;
    RuntimeException failure = null;
    for (Map.Entry<Integer, SharedListener> entry : List.copyOf(listenersByPort.entrySet())) {
      try {
        entry.getValue().listener.stop();
        listenersByPort.remove(entry.getKey());
      } catch (RuntimeException exception) {
        if (failure == null) failure = exception;
        else failure.addSuppressed(exception);
      }
    }
    portByConnection.clear();
    if (failure != null) throw failure;
  }

  private SharedListener listenerFor(int port) {
    SharedListener current = listenersByPort.get(port);
    if (current != null && current.listener.isRunning()) {
      return current;
    }
    HapiMLLPListener listener = new HapiMLLPListener(port, router);
    try {
      listener.start();
    } catch (RuntimeException failure) {
      throw new AnalyzerConnectionException("Cannot bind MLLP listener on port " + port, failure);
    }
    SharedListener shared = new SharedListener(listener);
    if (current != null) {
      shared.connections.addAll(current.connections);
      shared.bootHeld = current.bootHeld;
    }
    listenersByPort.put(port, shared);
    return shared;
  }

  private static final class SharedListener {

    private final HapiMLLPListener listener;
    private final Set<String> connections = new LinkedHashSet<>();
    private boolean bootHeld;

    private SharedListener(HapiMLLPListener listener) {
      this.listener = listener;
    }
  }
}
