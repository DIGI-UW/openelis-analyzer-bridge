package org.itech.ahb.file;

import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a single {@link SqliteFileStateStore} bean shared by the file
 * pipeline (FileWatcher) and the routing pipeline (HttpForwardingRouter).
 * <p>
 * Lives in the same package as {@link FileConfig} so the state-store path is
 * sourced from {@code bridge.file.state-store-path}. A single WAL-mode SQLite
 * database holds both the per-file {@code file_state} rows and the
 * {@code rejected_bundles} rows for forward-failure diagnostics, so the
 * bridge has exactly one durable store to back up / recover.
 * </p>
 * <p>
 * Guarded by {@code bridge.file.enabled}. The bean is created:
 * <ul>
 *   <li>when the property is explicitly {@code true}, and</li>
 *   <li>when the property is missing (default) — {@code matchIfMissing = true}
 *       keeps the bridge rejection-aware out of the box.</li>
 * </ul>
 * When the property is explicitly {@code false}, the bean is NOT created,
 * which disables both the file-ingestion pipeline AND rejection tracking.
 * Deployments that want rejection tracking without the file watcher must
 * leave {@code bridge.file.enabled} unset or {@code true}.
 * </p>
 */
@Configuration
@ConditionalOnProperty(prefix = "bridge.file", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class StateStoreConfig {

    @Bean(destroyMethod = "close")
    public SqliteFileStateStore fileStateStore(FileConfig fileConfig) {
        String path = fileConfig.getStateStorePath();
        log.info("Initializing SqliteFileStateStore at {}", path);
        return new SqliteFileStateStore(Paths.get(path));
    }
}
