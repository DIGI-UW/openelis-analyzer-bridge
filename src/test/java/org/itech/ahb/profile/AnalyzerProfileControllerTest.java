package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AnalyzerProfileControllerTest {

  private static final Path CONTRACT_FIXTURES = Path.of("contracts", "analyzer", "v1", "fixtures");
  private static final Instant NOW = Instant.parse("2026-08-19T03:45:00Z");

  @TempDir
  Path catalogDirectory;

  private ObjectMapper objectMapper;
  private ProfileFingerprintService fingerprints;
  private AnalyzerProfileCatalog catalog;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    fingerprints = new ProfileFingerprintService();
    Resource shipped = resource(publishedFixture("analyzer-profile-astm.json"));
    catalog = new AnalyzerProfileCatalog(
      catalogDirectory,
      List.of(shipped),
      objectMapper,
      Clock.fixed(NOW, ZoneOffset.UTC)
    );
    mockMvc = MockMvcBuilders.standaloneSetup(new AnalyzerProfileController(catalog))
      .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
      .build();
  }

  @Test
  void createsEditsAndPublishesADraftWithoutAnImmediateProfileRevision() throws Exception {
    MvcResult createdResult = mockMvc
      .perform(
        post("/api/profiles/drafts")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"actor\":\"profile-creator\",\"displayName\":\"Site File Profile\"}")
      )
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.kind").value("CREATE"))
      .andExpect(jsonPath("$.profile.catalog").doesNotExist())
      .andExpect(jsonPath("$.validationIssues").isNotEmpty())
      .andReturn();
    JsonNode created = objectMapper.readTree(createdResult.getResponse().getContentAsByteArray());
    String draftId = created.path("draftId").asText();
    String profileId = created.path("profile").path("profileMeta").path("id").asText();

    mockMvc
      .perform(get("/api/profiles"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.profiles.length()").value(1));
    mockMvc
      .perform(get("/api/profiles/drafts/{draftId}", draftId))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.createdBy").value("profile-creator"));

    ObjectNode candidate = authoredFixture("analyzer-profile-file.json", profileId, "Site File Profile");
    ObjectNode updateRequest = objectMapper.createObjectNode().put("actor", "profile-editor");
    updateRequest.set("profile", candidate);
    mockMvc
      .perform(
        put("/api/profiles/drafts/{draftId}", draftId)
          .contentType(MediaType.APPLICATION_JSON)
          .content(objectMapper.writeValueAsBytes(updateRequest))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.validationIssues").isEmpty())
      .andExpect(jsonPath("$.updatedBy").value("profile-editor"));

    mockMvc
      .perform(
        post("/api/profiles/drafts/{draftId}/publish", draftId)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"actor\":\"profile-publisher\"}")
      )
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.profile.profileMeta.id").value(profileId))
      .andExpect(jsonPath("$.profile.catalog.revision").value(1))
      .andExpect(jsonPath("$.profile.catalog.source").value("SITE"))
      .andExpect(jsonPath("$.publication.action").value("CREATED"));

    mockMvc.perform(get("/api/profiles/drafts")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    mockMvc
      .perform(get("/api/profiles/{profileId}", profileId).queryParam("revision", "1"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.profile.configDefaults.filePattern").value("*.{ods,ODS,xlsx,XLSX,xls,XLS}"));
  }

  @Test
  void duplicateUpdateAndStatusCommandsRemainExplicitAndNeverHardDelete() throws Exception {
    MvcResult duplicateResult = mockMvc
      .perform(
        post("/api/profiles/genexpert-astm/duplicate")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"actor\":\"profile-duplicator\",\"sourceRevision\":1,\"displayName\":\"Site GeneXpert Profile\"}")
      )
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.kind").value("DUPLICATE"))
      .andExpect(jsonPath("$.baseProfileId").value("genexpert-astm"))
      .andExpect(jsonPath("$.baseRevision").value(1))
      .andReturn();
    JsonNode duplicate = objectMapper.readTree(duplicateResult.getResponse().getContentAsByteArray());
    String duplicateDraftId = duplicate.path("draftId").asText();
    String profileId = duplicate.path("profile").path("profileMeta").path("id").asText();

    mockMvc
      .perform(
        post("/api/profiles/drafts/{draftId}/publish", duplicateDraftId)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"actor\":\"profile-publisher\"}")
      )
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.profile.catalog.lineage.parentProfileId").value("genexpert-astm"));

    MvcResult updateResult = mockMvc
      .perform(
        post("/api/profiles/{profileId}/update", profileId)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"actor\":\"profile-editor\",\"sourceRevision\":1}")
      )
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.kind").value("UPDATE"))
      .andReturn();
    String updateDraftId = objectMapper
      .readTree(updateResult.getResponse().getContentAsByteArray())
      .path("draftId")
      .asText();

    mockMvc
      .perform(
        post("/api/profiles/drafts/{draftId}/publish", updateDraftId)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"actor\":\"profile-publisher\"}")
      )
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.profile.catalog.revision").value(2))
      .andExpect(jsonPath("$.publication.action").value("UPDATED"));
    mockMvc
      .perform(
        post("/api/profiles/{profileId}/deactivate", profileId)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"actor\":\"profile-deactivator\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.profile.catalog.status").value("INACTIVE"));
    mockMvc
      .perform(
        post("/api/profiles/{profileId}/reactivate", profileId)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"actor\":\"profile-reactivator\"}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.profile.catalog.status").value("ACTIVE"));
    mockMvc
      .perform(get("/api/profiles/{profileId}/history", profileId))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.length()").value(4));
    mockMvc.perform(delete("/api/profiles/{profileId}", profileId)).andExpect(status().isMethodNotAllowed());

    assertThat(
      catalog.require("genexpert-astm", 1).profile().path("profileMeta").path("displayName").asText()
    ).isEqualTo("Cepheid GeneXpert (ASTM Mode)");
  }

  @Test
  void rejectedPublishReturnsVisibleValidationAndKeepsTheDraft() throws Exception {
    MvcResult created = mockMvc
      .perform(
        post("/api/profiles/drafts")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"actor\":\"profile-creator\",\"displayName\":\"Incomplete Profile\"}")
      )
      .andExpect(status().isCreated())
      .andReturn();
    String draftId = objectMapper.readTree(created.getResponse().getContentAsByteArray()).path("draftId").asText();

    mockMvc
      .perform(
        post("/api/profiles/drafts/{draftId}/publish", draftId)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"actor\":\"profile-publisher\"}")
      )
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("cannot be published")));
    mockMvc
      .perform(get("/api/profiles/drafts/{draftId}", draftId))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.draftId").value(draftId));
  }

  @Test
  void catalogExposesHumanReadableControlRecognitionForEachRevision() throws Exception {
    ObjectNode profile = publishedFixture("analyzer-profile-astm.json");
    mockMvc
      .perform(get("/api/profiles"))
      .andExpect(status().isOk())
      .andExpect(
        jsonPath("$.profiles[0].controlRecognitionSummary.recognitionFingerprint")
          .value(profile.path("catalog").path("recognitionFingerprint").asText())
      )
      .andExpect(jsonPath("$.profiles[0].controlRecognitionSummary.mode").value("RULES"))
      .andExpect(jsonPath("$.profiles[0].controlRecognitionSummary.affirmedNoControlResults").value(false))
      .andExpect(jsonPath("$.profiles[0].controlRecognitionSummary.conditions[0].key").value("astm-order-action-control"))
      .andExpect(
        jsonPath("$.profiles[0].controlRecognitionSummary.conditions[0].description")
          .value("Order field 12 equals Q")
      );
  }

  @Test
  void readsAndUpdatesControlRecognitionThroughTheSafeDraftAuthoringContract() throws Exception {
    MvcResult duplicateResult = mockMvc
      .perform(
        post("/api/profiles/genexpert-astm/duplicate")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"actor\":\"profile-duplicator\",\"sourceRevision\":1,\"displayName\":\"Safe Recognition Profile\"}"
          )
      )
      .andExpect(status().isCreated())
      .andReturn();
    String draftId = objectMapper
      .readTree(duplicateResult.getResponse().getContentAsByteArray())
      .path("draftId")
      .asText();

    MvcResult authoringResult = mockMvc
      .perform(get("/api/profiles/drafts/{draftId}/control-recognition", draftId))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.draftId").value(draftId))
      .andExpect(jsonPath("$.kind").value("DUPLICATE"))
      .andExpect(jsonPath("$.displayName").value("Safe Recognition Profile"))
      .andExpect(jsonPath("$.recognition.mode").value("RULES"))
      .andExpect(jsonPath("$.recognition.conditions[0].description").value("Order field 12 equals Q"))
      .andReturn();
    assertThat(authoringResult.getResponse().getContentAsString())
      .doesNotContain("O.12")
      .doesNotContain("targetField")
      .doesNotContain("ruleType");

    mockMvc
      .perform(
        put("/api/profiles/drafts/{draftId}/control-recognition", draftId)
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"actor\":\"profile-editor\",\"mode\":\"NONE\",\"affirmedNoControlResults\":true,\"conditions\":[]}"
          )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.recognition.mode").value("NONE"))
      .andExpect(jsonPath("$.recognition.affirmedNoControlResults").value(true))
      .andExpect(jsonPath("$.updatedBy").value("profile-editor"))
      .andExpect(jsonPath("$.validationIssues").isEmpty());

    assertThat(
      catalog
        .requireDraft(draftId)
        .profile()
        .path("controlResultRecognition")
        .path("affirmedNoControlResults")
        .asBoolean()
    ).isTrue();
    assertThat(catalog.requireDraft(draftId).profile().path("controlResultRecognition").has("rules")).isFalse();
  }

  @Test
  void rejectsUnaffirmedNoneWithoutChangingTheDraft() throws Exception {
    MvcResult duplicateResult = mockMvc
      .perform(
        post("/api/profiles/genexpert-astm/duplicate")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"actor\":\"profile-duplicator\",\"sourceRevision\":1,\"displayName\":\"Unchanged Recognition Profile\"}"
          )
      )
      .andReturn();
    String draftId = objectMapper
      .readTree(duplicateResult.getResponse().getContentAsByteArray())
      .path("draftId")
      .asText();

    mockMvc
      .perform(
        put("/api/profiles/drafts/{draftId}/control-recognition", draftId)
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            "{\"actor\":\"profile-editor\",\"mode\":\"NONE\",\"affirmedNoControlResults\":false,\"conditions\":[]}"
          )
      )
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("affirm")));

    assertThat(
      catalog.requireDraft(draftId).profile().path("controlResultRecognition").path("mode").asText()
    ).isEqualTo("RULES");
  }

  private ObjectNode publishedFixture(String filename) throws Exception {
    ObjectNode profile = (ObjectNode) objectMapper.readTree(CONTRACT_FIXTURES.resolve(filename).toFile());
    ObjectNode catalogMetadata = (ObjectNode) profile.path("catalog");
    catalogMetadata.put(
      "recognitionFingerprint",
      fingerprints.recognitionFingerprint(profile.path("controlResultRecognition"))
    );
    catalogMetadata.put("revisionFingerprint", fingerprints.revisionFingerprint(profile));
    return profile;
  }

  private ObjectNode authoredFixture(String filename, String profileId, String displayName) throws Exception {
    ObjectNode profile = publishedFixture(filename);
    profile.remove("catalog");
    ObjectNode metadata = (ObjectNode) profile.path("profileMeta");
    metadata.put("id", profileId);
    metadata.put("displayName", displayName);
    return profile;
  }

  private Resource resource(ObjectNode profile) throws Exception {
    byte[] content = objectMapper.writeValueAsBytes(profile);
    return new ByteArrayResource(content) {
      @Override
      public String getFilename() {
        return profile.path("profileMeta").path("id").asText() + ".json";
      }
    };
  }
}
