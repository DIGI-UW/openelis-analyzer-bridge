package org.itech.ahb.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

  private static final Random FIXED = new Random(42);

  @Test
  @DisplayName("grows exponentially from the base delay")
  void growsExponentially() {
    BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(5), 2.0, Duration.ofHours(1), 0.0, FIXED);

    assertEquals(Duration.ofSeconds(5), policy.delayAfter(1));
    assertEquals(Duration.ofSeconds(10), policy.delayAfter(2));
    assertEquals(Duration.ofSeconds(20), policy.delayAfter(3));
    assertEquals(Duration.ofSeconds(40), policy.delayAfter(4));
  }

  @Test
  @DisplayName("settles at the ceiling so a long outage becomes steady polling")
  void capsAtTheCeiling() {
    BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(5), 2.0, Duration.ofMinutes(10), 0.0, FIXED);

    assertEquals(Duration.ofMinutes(10), policy.delayAfter(20));
    assertEquals(Duration.ofMinutes(10), policy.delayAfter(150), "no overflow, however many attempts have passed");
  }

  @Test
  @DisplayName("spreads retries so deliveries that failed together do not return together")
  void appliesJitterWithinBounds() {
    BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(10), 2.0, Duration.ofMinutes(10), 0.2, FIXED);

    boolean sawVariation = false;
    Duration previous = null;
    for (int i = 0; i < 50; i++) {
      Duration delay = policy.delayAfter(1);
      assertTrue(delay.toMillis() >= 8_000, "jitter must not collapse the delay: " + delay);
      assertTrue(delay.toMillis() <= 12_000, "jitter must not inflate the delay: " + delay);
      if (previous != null && !previous.equals(delay)) {
        sawVariation = true;
      }
      previous = delay;
    }
    assertTrue(sawVariation, "without variation every analyzer would retry in lockstep");
  }

  @Test
  @DisplayName("never schedules an attempt in the past")
  void neverReturnsZero() {
    BackoffPolicy policy = new BackoffPolicy(Duration.ZERO, 2.0, Duration.ZERO, 0.9, FIXED);
    assertTrue(policy.delayAfter(1).toMillis() >= 1);
  }
}
