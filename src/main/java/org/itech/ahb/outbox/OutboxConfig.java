package org.itech.ahb.outbox;

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
    log.info("Initializing delivery outbox at {}", properties.getDbPath());
    return new SqliteOutboxStore(Paths.get(properties.getDbPath()));
  }
}
