package org.itech.ahb.connection;

/** Lifecycle boundary for HL7 listeners owned by saved Bridge connections. */
public interface Hl7ConnectionListeners {
  /** Joins, or opens, the shared MLLP listener on {@code port} for one active connection. */
  void start(String connectionId, int port);
  void stop(String connectionId);

  /** Whether a Bridge MLLP listener, boot-held or shared by active connections, is running on {@code port}. */
  boolean isListening(int port);
}
