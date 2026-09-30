package org.itech.ahb.outbox;

import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the delivery outbox.
 *
 * <p>Deliberately has no {@code @ConditionalOnProperty}. The store that recorded forward failures
 * before this existed was gated on {@code bridge.file.enabled}, so switching off the file watcher
 * silently switched off failure tracking for every transport. A store that guarantees no received
 * result is lost cannot be something a deployment can turn off by accident.
 */
@Configuration
@Slf4j
public class OutboxConfig {

  @Bean(destroyMethod = "close")
  public OutboxStore outboxStore(OutboxProperties properties) {
    Path dbPath = Paths.get(properties.getDbPath());
    log.info("Initializing delivery outbox at {}", dbPath);
    if (isInTemporaryDirectory(dbPath)) {
      log.warn(
        "The delivery outbox is in the temporary directory ({}). Results OpenELIS has not accepted yet are lost when " +
        "it is cleared or the container is recreated. Set bridge.outbox.db-path to a persistent volume.",
        dbPath
      );
    }
    return new SqliteOutboxStore(dbPath);
  }

  static boolean isInTemporaryDirectory(Path dbPath) {
    Path temporary = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
    return dbPath.toAbsolutePath().normalize().startsWith(temporary);
  }
}
