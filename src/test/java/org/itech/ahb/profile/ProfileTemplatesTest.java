package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A profile written from a template is valid with no edits beyond the instrument's own facts. */
class ProfileTemplatesTest {

  @TempDir
  Path catalogDirectory;

  @ParameterizedTest
  @ValueSource(strings = { "astm", "hl7", "file" })
  void aTemplateIsAValidDraftAsItStands(String protocol) throws Exception {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    AnalyzerProfileCatalog catalog = new AnalyzerProfileCatalog(
      catalogDirectory,
      List.of(),
      objectMapper,
      Clock.systemUTC(),
      UUID::randomUUID
    );
    var draft = catalog.createDraft("Written from the " + protocol + " template", "author");
    ObjectNode template = (ObjectNode) objectMapper.readTree(Path.of("templates", protocol + ".json").toFile());

    var edited = catalog.updateDraft(draft.draftId(), template, "author");

    assertThat(edited.validationIssues()).isEmpty();
    assertThat(edited.profile().path("schemaVersion").asText()).isEqualTo("2.0");
  }
}
