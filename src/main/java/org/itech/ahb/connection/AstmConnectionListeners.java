package org.itech.ahb.connection;

/** Lifecycle boundary for ASTM listeners owned by durable Bridge connections. */
public interface AstmConnectionListeners {

  void start(
    String connectionId,
    String sourceBindingId,
    String analyzerId,
    int port,
    String lowerLayerVersion
  );

  void stop(String connectionId);
}
