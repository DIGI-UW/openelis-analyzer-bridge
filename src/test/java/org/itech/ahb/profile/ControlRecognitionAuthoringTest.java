package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControlRecognitionAuthoringTest {

  private static final Path FIXTURES = Path.of("contracts", "analyzer", "v1", "fixtures");

  private ObjectMapper objectMapper;
  private ControlRecognitionAuthoring authoring;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    authoring = new ControlRecognitionAuthoring(objectMapper);
  }

  @Test
  void rendersFieldRulesWithoutRawMatcherFieldsOrProfileSpecificKnowledge() throws Exception {
    ObjectNode profile = fixture("analyzer-profile-astm.json");

    ControlRecognitionAuthoring.View view = authoring.inspect(profile);

    assertThat(view.mode()).isEqualTo("RULES");
    assertThat(view.affirmedNoControlResults()).isFalse();
    assertThat(view.conditions()).singleElement().satisfies(condition -> {
      assertThat(condition.key()).isEqualTo("astm-order-action-control");
      assertThat(condition.kind()).isEqualTo("FIELD_VALUE_EQUALS");
      assertThat(condition.sourceLabel()).isEqualTo("Order field 12");
      assertThat(condition.description()).isEqualTo("Order field 12 equals Q");
      assertThat(condition.value()).isEqualTo("Q");
      assertThat(condition.editable()).isTrue();
    });

    String responseJson = objectMapper.writeValueAsString(view);
    assertThat(responseJson)
      .doesNotContain("O.12")
      .doesNotContain("targetField")
      .doesNotContain("ruleType");
  }

  @Test
  void hidesConfiguredRegularExpressionsWhilePreservingTheirMeaning() throws Exception {
    ObjectNode profile = fixture("analyzer-profile-quantstudio.json");

    ControlRecognitionAuthoring.Condition condition = authoring.inspect(profile)
      .conditions()
      .stream()
      .filter(candidate -> "negative-control-prefix".equals(candidate.key()))
      .findFirst()
      .orElseThrow();

    assertThat(condition.kind()).isEqualTo("CONFIGURED_SPECIMEN_ID_PATTERN");
    assertThat(condition.description()).isEqualTo("Specimen ID matches a configured pattern");
    assertThat(condition.value()).isNull();
    assertThat(condition.editable()).isFalse();
    assertThat(objectMapper.writeValueAsString(condition)).doesNotContain("^(CNEG|NTC)");
  }

  @Test
  void appliesStructuredRulesToTheExistingProfileContract() throws Exception {
    ObjectNode profile = fixture("analyzer-profile-astm.json");
    ControlRecognitionAuthoring.Condition current = authoring.inspect(profile).conditions().get(0);

    ObjectNode updated = authoring.apply(
      profile,
      new ControlRecognitionAuthoring.Update(
        "RULES",
        false,
        List.of(
          new ControlRecognitionAuthoring.ConditionInput(
            current.key(),
            "FIELD_VALUE_EQUALS",
            current.sourceKey(),
            "CONTROL",
            null,
            null
          ),
          new ControlRecognitionAuthoring.ConditionInput(
            null,
            "SPECIMEN_ID_STARTS_WITH",
            null,
            "QC-",
            "Level 1",
            "Control"
          )
        )
      )
    );

    assertThat(updated.path("controlResultRecognition").path("mode").asText()).isEqualTo("RULES");
    assertThat(
      updated
        .path("controlResultRecognition")
        .path("rules")
        .path("astm-order-action-control")
        .path("targetField")
        .asText()
    ).isEqualTo("O.12");
    assertThat(
      updated
        .path("controlResultRecognition")
        .path("rules")
        .path("astm-order-action-control")
        .path("operand")
        .asText()
    ).isEqualTo("CONTROL");
    assertThat(updated.path("controlResultRecognition").path("rules").size()).isEqualTo(2);
    assertThat(
      updated
        .path("controlResultRecognition")
        .path("rules")
        .elements()
        .next()
        .has("affirmedNoControlResults")
    ).isFalse();
  }

  @Test
  void requiresAffirmationForNoneAndRemovesRulesWhenAffirmed() throws Exception {
    ObjectNode profile = fixture("analyzer-profile-astm.json");

    assertThatThrownBy(
      () -> authoring.apply(profile, new ControlRecognitionAuthoring.Update("NONE", false, List.of()))
    )
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("affirm");

    ObjectNode updated = authoring.apply(
      profile,
      new ControlRecognitionAuthoring.Update("NONE", true, List.of())
    );

    assertThat(updated.path("controlResultRecognition").path("mode").asText()).isEqualTo("NONE");
    assertThat(
      updated.path("controlResultRecognition").path("affirmedNoControlResults").asBoolean()
    ).isTrue();
    assertThat(updated.path("controlResultRecognition").has("rules")).isFalse();
  }

  @Test
  void rejectsEmptyRulesAndUnknownStructuredSources() throws Exception {
    ObjectNode profile = fixture("analyzer-profile-astm.json");

    assertThatThrownBy(
      () -> authoring.apply(profile, new ControlRecognitionAuthoring.Update("RULES", false, List.of()))
    )
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("at least one condition");

    ControlRecognitionAuthoring.ConditionInput unknownSource = new ControlRecognitionAuthoring.ConditionInput(
      "new-condition",
      "FIELD_VALUE_EQUALS",
      "unknown-source",
      "Q",
      null,
      null
    );
    assertThatThrownBy(
      () ->
        authoring.apply(
          profile,
          new ControlRecognitionAuthoring.Update("RULES", false, List.of(unknownSource))
        )
    )
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("source");
  }

  private ObjectNode fixture(String filename) throws Exception {
    return (ObjectNode) objectMapper.readTree(FIXTURES.resolve(filename).toFile());
  }
}
