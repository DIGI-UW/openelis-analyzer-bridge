package org.itech.ahb.mllp;

import java.time.Duration;

/**
 * Bounds on what unauthenticated peers of an MLLP port may hold: concurrent connections, the bytes
 * of one message, and the time from a message's start block to its end block. An idle connection
 * between messages is not limited in time.
 */
public record MllpLimits(int maxConnections, int maxMessageBytes, Duration messageTimeout) {
  public static final MllpLimits DEFAULTS = new MllpLimits(64, 16 * 1024 * 1024, Duration.ofSeconds(60));

  public MllpLimits {
    if (maxConnections < 1) throw new IllegalArgumentException("maxConnections must be positive");
    if (maxMessageBytes < 1) throw new IllegalArgumentException("maxMessageBytes must be positive");
    if (messageTimeout == null || messageTimeout.isNegative() || messageTimeout.isZero()) {
      throw new IllegalArgumentException("messageTimeout must be positive");
    }
  }
}
