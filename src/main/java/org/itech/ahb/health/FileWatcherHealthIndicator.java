package org.itech.ahb.health;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.file.FileWatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("filewatcher")
@ConditionalOnEnabledHealthIndicator("filewatcher")
@Slf4j
public class FileWatcherHealthIndicator implements HealthIndicator {

    private final FileWatcher watcher;

    public FileWatcherHealthIndicator(@Autowired(required = false) FileWatcher watcher) {
        this.watcher = watcher;
    }

    @Override
    public Health health() {
        if (watcher == null) {
            return Health.unknown()
                .withDetail("reason", "File watcher not configured")
                .build();
        }

        if (watcher.isRunning()) {
            return Health.up()
                .withDetail("status", "watching for files")
                .build();
        }

        return Health.down()
            .withDetail("status", "not running")
            .build();
    }
}
