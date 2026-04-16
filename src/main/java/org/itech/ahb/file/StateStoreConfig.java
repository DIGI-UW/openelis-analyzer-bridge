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
 * Guarded by {@code bridge.file.enabled}: when disabled (e.g. pure-ASTM
 * deployments that still want rejection tracking), the bean is still created
 * because rejections come from the router, not the watcher. The existing
 * default {@code matchIfMissing = true} keeps the bridge rejection-aware in
 * all environments; explicit disable requires setting the property to false.
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
