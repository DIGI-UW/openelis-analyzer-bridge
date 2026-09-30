package org.itech.ahb.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class OutboxConfigTest {

  private static final Path TEMPORARY = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();

  @Test
  void theDefaultOutboxPathIsInTheTemporaryDirectory() {
    assertThat(OutboxConfig.isInTemporaryDirectory(Paths.get(new OutboxProperties().getDbPath()))).isTrue();
  }

  @Test
  void theImagesDataVolumeIsNotTheTemporaryDirectory() {
    assertThat(OutboxConfig.isInTemporaryDirectory(Paths.get("/data/openelis-analyzer-bridge/outbox.db"))).isFalse();
  }

  @Test
  void aSiblingWhoseNameStartsLikeTheTemporaryDirectoryIsNotInsideIt() {
    Path sibling = TEMPORARY.resolveSibling(TEMPORARY.getFileName() + "-persistent").resolve("outbox.db");

    assertThat(OutboxConfig.isInTemporaryDirectory(sibling)).isFalse();
  }
}
