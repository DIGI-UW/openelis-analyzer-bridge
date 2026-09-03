package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProfileCatalogPropertiesTest {

  @Test
  void defaultsToTheBridgeImagesPersistentDataVolume() {
    assertThat(new ProfileCatalogProperties().getDirectory())
      .isEqualTo("/data/openelis-analyzer-bridge/profile-catalog");
  }

  @Test
  void productionImageBuildIncludesTheRuntimeContractSchemas() throws Exception {
    assertThat(Files.readString(Path.of("Dockerfile"))).contains("ADD ./contracts /build/contracts");
  }
}
