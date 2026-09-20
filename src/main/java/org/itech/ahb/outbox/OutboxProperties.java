package org.itech.ahb.outbox;

import java.nio.file.Paths;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for the durable delivery outbox. */
@Configuration
@ConfigurationProperties(prefix = "bridge.outbox")
@Data
public class OutboxProperties {

  /**
   * SQLite database holding received results until OpenELIS accepts them.
   *
   * <p>Deliberately a separate file from {@code bridge.file.state-store-path}: the outbox is the only
   * copy of a received result, so it is opened with stricter durability, and it must not share a
   * lifecycle with a store that can be switched off.
   *
   * <p>The default lives under the JVM temp directory so tests and local runs work with no
   * configuration. Production deployments must point this at a persistent volume.
   *
   * <p><b>Invariant:</b> one bridge process per database file.
   */
  private String dbPath = Paths.get(
    System.getProperty("java.io.tmpdir"),
    "openelis-analyzer-bridge",
    "outbox.db"
  ).toString();

  /**
   * How long the dispatcher waits when it finds no due work. The write path wakes it directly, so
   * this is a safety net rather than the normal path to delivery.
   */
  private Duration pollInterval = Duration.ofSeconds(1);

  /**
   * How long a claimed entry stays leased. Must exceed connect plus read timeout, or a slow delivery
   * could be reclaimed while it is still in flight.
   */
  private Duration lease = Duration.ofSeconds(120);

  /** Retry schedule for failures that can still succeed. */
  private Retry retry = new Retry();

  /** How long terminal entries are kept before being purged. */
  private Retention retention = new Retention();

  /**
   * Whether the payload endpoints serve clinical content. Access is audited, and can be switched off
   * entirely for deployments where the bridge host is not a permitted place to read results.
   */
  private boolean payloadAccessEnabled = true;

  @Data
  public static class Retry {

    /**
     * How many delivery attempts before an entry is dead-lettered.
     *
     * <p>Sized for an overnight OpenELIS outage rather than a brief blip: at the capped interval this
     * is roughly a day of retrying. Dead-lettering a day's results because OpenELIS was down for an
     * evening would recreate the failure this outbox exists to prevent.
     */
    private int maxAttempts = 150;

    /** Delay before the first retry. */
    private Duration baseDelay = Duration.ofSeconds(5);

    /** Growth factor applied to each successive delay. */
    private double multiplier = 2.0;

    /** Ceiling on the delay, so a long outage settles into steady polling. */
    private Duration maxDelay = Duration.ofMinutes(10);

    /**
     * Random proportion applied to each delay, so that analyzers whose deliveries failed together do
     * not retry in lockstep against a recovering OpenELIS.
     */
    private double jitter = 0.2;
  }

  @Data
  public static class Retention {

    /** How long a delivered entry is kept as proof of delivery. */
    private Duration delivered = Duration.ofDays(30);

    /**
     * How long a dead-lettered entry is kept after an operator dismisses it. Entries nobody has
     * dismissed are never purged, whatever their age.
     */
    private Duration dismissed = Duration.ofDays(90);
  }
}
