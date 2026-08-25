package org.itech.ahb.connection;

/** Owns serial listeners created from active, profile-pinned Bridge connections. */
public interface SerialConnectionListeners {

  void start(
    String connectionId,
    String sourceBindingId,
    String analyzerId,
    String portPath,
    SerialConnectionSettings settings
  );

  void stop(String connectionId);
}
