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

  /** Restore previously activated ownership even while a device is temporarily unavailable. */
  default void restore(
    String connectionId,
    String sourceBindingId,
    String analyzerId,
    String portPath,
    SerialConnectionSettings settings
  ) {
    start(connectionId, sourceBindingId, analyzerId, portPath, settings);
  }

  void stop(String connectionId);
}
