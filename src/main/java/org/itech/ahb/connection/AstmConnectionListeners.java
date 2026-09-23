package org.itech.ahb.connection;

/** Lifecycle boundary for ASTM listeners owned by durable Bridge connections. */
public interface AstmConnectionListeners {

  /** Joins, or opens, the shared listener on {@code port} for one active connection. */
  void start(
    String connectionId,
    String analyzerId,
    int port,
    String lowerLayerVersion
  );

  void stop(String connectionId);
}
