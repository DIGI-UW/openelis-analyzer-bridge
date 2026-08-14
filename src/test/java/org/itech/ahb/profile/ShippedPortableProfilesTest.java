package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class ShippedPortableProfilesTest {

  private static final Path MIGRATION_REPORT = Path.of("contracts", "analyzer", "v1", "profile-migration-report.json");

  @TempDir
  Path catalogDirectory;

  @Test
  void packagesEveryLegacyProfileWithoutCollapsingSourceRows() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    JsonNode report = objectMapper.readTree(Files.readString(MIGRATION_REPORT));
    Resource[] resources = new PathMatchingResourcePatternResolver()
      .getResources("classpath*:/analyzer-profiles/**/*.json");

    PortableProfileCatalog catalog = new PortableProfileCatalog(
      catalogDirectory,
      Arrays.asList(resources),
      objectMapper,
      Clock.systemUTC()
    );
    var entries = catalog.list(ProfileCatalogFilter.all());
    Map<String, ProfileCatalogEntry> byId = entries
      .stream()
      .collect(Collectors.toMap(entry -> entry.profile().path("profileId").asText(), Function.identity()));

    assertThat(entries).hasSize(20);
    assertThat(entries).filteredOn(entry -> "ASTM".equals(entry.profile().path("protocol").asText())).hasSize(6);
    assertThat(entries).filteredOn(entry -> "HL7".equals(entry.profile().path("protocol").asText())).hasSize(7);
    assertThat(entries).filteredOn(entry -> "FILE".equals(entry.profile().path("protocol").asText())).hasSize(7);
    assertThat(entries).allSatisfy(entry -> assertThat(entry.profile().path("source").asText()).isEqualTo("SHIPPED"));

    assertThat(byId.get("dtprime").profile().path("status").asText()).isEqualTo("INACTIVE");
    assertThat(report.path("profiles").path("dtprime").path("issues").findValuesAsText("code")).containsExactly(
      "UNSUPPORTED_RUNTIME_FORMAT"
    );
    assertThat(entries)
      .filteredOn(entry -> "ACTIVE".equals(entry.profile().path("status").asText()))
      .filteredOn(entry -> "FILE".equals(entry.profile().path("protocol").asText()))
      .allSatisfy(entry -> assertThat(entry.profile().path("file").path("format").asText()).isNotEqualTo("XML"));

    int sourceTestRows = report.path("summary").path("sourceTestRows").asInt();
    int portableTestRows = entries.stream().mapToInt(entry -> entry.profile().path("tests").size()).sum();
    int sourceQcRows = report.path("summary").path("sourceQcRows").asInt();
    int portableQcRows = entries.stream().mapToInt(entry -> entry.profile().path("qcIdentification").size()).sum();
    assertThat(portableTestRows).isEqualTo(sourceTestRows);
    assertThat(portableQcRows).isEqualTo(sourceQcRows);

    assertThat(report.path("summary").path("profileCount").asInt()).isEqualTo(20);
    assertThat(report.path("profiles").size()).isEqualTo(20);
    assertThat(report.path("profiles").findValuesAsText("sourceSha256")).allSatisfy(
      hash -> assertThat(hash).matches("^[a-f0-9]{64}$")
    );
  }
}
