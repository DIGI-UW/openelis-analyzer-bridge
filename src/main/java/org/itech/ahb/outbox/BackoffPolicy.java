package org.itech.ahb.outbox;

import java.time.Duration;
import java.util.Random;

/** Exponential backoff with a ceiling and jitter. */
public class BackoffPolicy {

  private final Duration baseDelay;
  private final double multiplier;
  private final Duration maxDelay;
  private final double jitter;
  private final Random random;

  public BackoffPolicy(Duration baseDelay, double multiplier, Duration maxDelay, double jitter, Random random) {
    this.baseDelay = baseDelay;
    this.multiplier = multiplier <= 1.0 ? 1.0 : multiplier;
    this.maxDelay = maxDelay;
    this.jitter = Math.max(0.0, Math.min(1.0, jitter));
    this.random = random;
  }

  public static BackoffPolicy from(OutboxProperties.Retry retry) {
    return new BackoffPolicy(
      retry.getBaseDelay(),
      retry.getMultiplier(),
      retry.getMaxDelay(),
      retry.getJitter(),
      new Random()
    );
  }

  /**
   * How long to wait before attempt {@code attemptsSoFar + 1}.
   *
   * <p>Jitter matters more than it looks: when OpenELIS goes down, every analyzer's deliveries fail
   * within the same second, and without it they would all retry in lockstep and hit the recovering
   * server together.
   */
  public Duration delayAfter(int attemptsSoFar) {
    int exponent = Math.max(0, attemptsSoFar - 1);
    double scaled = baseDelay.toMillis() * Math.pow(multiplier, exponent);
    long capped = (long) Math.min(scaled, (double) maxDelay.toMillis());
    if (jitter > 0) {
      double factor = 1.0 + ((random.nextDouble() * 2.0 - 1.0) * jitter);
      capped = (long) Math.max(1, capped * factor);
    }
    return Duration.ofMillis(Math.max(1, capped));
  }
}
