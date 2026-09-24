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

  /** Whether a Bridge listener, boot-held or shared by active connections, is running on {@code port}. */
  boolean isListening(int port);
}
