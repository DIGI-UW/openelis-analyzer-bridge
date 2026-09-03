package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

class AnalyzerProfileCatalogTest {

  private static final Path CONTRACT_FIXTURES = Path.of("contracts", "analyzer", "v1", "fixtures");
  private static final Instant NOW = Instant.parse("2026-08-19T03:45:00Z");

  @TempDir
  Path catalogDirectory;

  private ObjectMapper objectMapper;
  private ProfileFingerprintService fingerprints;
  private List<Resource> shippedProfiles;
  private AnalyzerProfileCatalog catalog;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    fingerprints = new ProfileFingerprintService();
    shippedProfiles = List.of(
      resource(publishedFixture("analyzer-profile-astm.json")),
      resource(publishedFixture("analyzer-profile-file.json"))
    );
    catalog = new AnalyzerProfileCatalog(
      catalogDirectory,
      shippedProfiles,
      objectMapper,
      Clock.fixed(NOW, ZoneOffset.UTC),
      new ArrayDeque<>(
        List.of(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          UUID.fromString("00000000-0000-0000-0000-000000000002"),
          UUID.fromString("00000000-0000-0000-0000-000000000003"),
          UUID.fromString("00000000-0000-0000-0000-000000000004"),
          UUID.fromString("00000000-0000-0000-0000-000000000005"),
          UUID.fromString("00000000-0000-0000-0000-000000000006"),
          UUID.fromString("00000000-0000-0000-0000-000000000007"),
          UUID.fromString("00000000-0000-0000-0000-000000000008")
        )
      )::removeFirst
    );
  }

  @Test
  void createRemainsAnEditableDurableDraftUntilExplicitPublish() throws Exception {
    var draft = catalog.createDraft("Site Fluoro Profile", "profile-creator");
    String profileId = draft.profile().path("profileMeta").path("id").asText();

    assertThat(draft.kind().name()).isEqualTo("CREATE");
    assertThat(profileId).startsWith("site.");
    assertThat(draft.profile().has("catalog")).isFalse();
    assertThat(draft.validationIssues()).isNotEmpty();
    assertThat(catalog.latest()).hasSize(2);

    AnalyzerProfileCatalog reopenedDrafts = new AnalyzerProfileCatalog(
      catalogDirectory,
      shippedProfiles,
      objectMapper,
      Clock.fixed(NOW, ZoneOffset.UTC)
    );
    assertThat(reopenedDrafts.requireDraft(draft.draftId())).isEqualTo(draft);

    ObjectNode completeProfile = authoredFixture("analyzer-profile-file.json", profileId, "Site Fluoro Profile");
    var completeDraft = catalog.updateDraft(draft.draftId(), completeProfile, "profile-editor");
    assertThat(completeDraft.validationIssues()).isEmpty();

    ProfileRevision published = catalog.publishDraft(draft.draftId(), "profile-publisher");
    ObjectNode profile = published.profile();
    assertThat(profile.path("profileMeta").path("id").asText()).isEqualTo(profileId);
    assertThat(profile.path("catalog").path("revision").asInt()).isEqualTo(1);
    assertThat(profile.path("catalog").path("source").asText()).isEqualTo("SITE");
    assertThat(profile.path("catalog").path("status").asText()).isEqualTo("ACTIVE");
    assertThat(profile.path("catalog").path("publishedBy").asText()).isEqualTo("profile-publisher");
    assertThat(profile.path("catalog").path("publishedAt").asText()).isEqualTo(NOW.toString());
    assertThat(profile.path("catalog").path("revisionFingerprint").asText()).isEqualTo(
      fingerprints.revisionFingerprint(profile)
    );
    assertThat(published.publication().action()).isEqualTo(ProfileAuditAction.CREATED);
    assertThat(catalog.drafts()).isEmpty();

    AnalyzerProfileCatalog reopenedPublished = new AnalyzerProfileCatalog(
      catalogDirectory,
      shippedProfiles,
      objectMapper,
      Clock.fixed(NOW, ZoneOffset.UTC)
    );
    assertThat(reopenedPublished.require(profileId, 1)).isEqualTo(published);
  }

  @Test
  void duplicateCreatesAnIndependentDraftWithLineageAndUnchangedProfileData() throws Exception {
    ObjectNode source = catalog.require("genexpert-astm", 1).profile();

    var draft = catalog.duplicateDraft("genexpert-astm", 1, "Site GeneXpert Profile", "profile-duplicator");
    ObjectNode duplicate = draft.profile();

    assertThat(draft.kind().name()).isEqualTo("DUPLICATE");
    assertThat(draft.baseProfileId()).isEqualTo("genexpert-astm");
    assertThat(draft.baseRevision()).isEqualTo(1);
    assertThat(duplicate.path("profileMeta").path("id").asText()).startsWith("site.");
    assertThat(duplicate.path("profileMeta").path("displayName").asText()).isEqualTo("Site GeneXpert Profile");
    assertThat(duplicate.path("protocol")).isEqualTo(source.path("protocol"));
    assertThat(duplicate.path("configDefaults")).isEqualTo(source.path("configDefaults"));
    assertThat(duplicate.path("default_test_mappings")).isEqualTo(source.path("default_test_mappings"));
    assertThat(duplicate.path("controlResultRecognition")).isEqualTo(source.path("controlResultRecognition"));
    assertThat(duplicate.has("catalog")).isFalse();
    assertThat(draft.validationIssues()).isEmpty();

    ProfileRevision published = catalog.publishDraft(draft.draftId(), "profile-publisher");
    assertThat(published.profile().path("catalog").path("lineage").path("parentProfileId").asText()).isEqualTo(
      "genexpert-astm"
    );
    assertThat(published.profile().path("catalog").path("lineage").path("parentRevision").asInt()).isEqualTo(1);
    assertThat(published.publication().action()).isEqualTo(ProfileAuditAction.DUPLICATED);
    assertThat(catalog.require("genexpert-astm", 1).profile()).isEqualTo(source);
  }

  @Test
  void duplicateRejectsNormalizedNamesAlreadyUsedByPublishedProfilesOrDrafts() {
    assertThatThrownBy(
      () -> catalog.duplicateDraft("genexpert-astm", 1, "  bruker fluorocycler xt  ", "profile-duplicator")
    )
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("displayName already exists");

    catalog.duplicateDraft("genexpert-astm", 1, "Site GeneXpert Profile", "profile-duplicator");

    assertThatThrownBy(
      () -> catalog.duplicateDraft("genexpert-astm", 1, " SITE GENEXPERT PROFILE ", "profile-duplicator")
    )
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("displayName already exists");
  }

  @Test
  void updateSharedPublishesANewRevisionWithoutMovingOrMutatingThePreviousRevision() throws Exception {
    var duplicate = catalog.duplicateDraft("genexpert-astm", 1, "Shared Site Profile", "profile-duplicator");
    ProfileRevision revisionOne = catalog.publishDraft(duplicate.draftId(), "profile-publisher");
    String profileId = revisionOne.profile().path("profileMeta").path("id").asText();

    var firstSuccessor = catalog.updateSharedDraft(profileId, 1, "profile-editor");
    var staleSuccessor = catalog.updateSharedDraft(profileId, 1, "second-editor");
    ObjectNode candidate = firstSuccessor.profile();
    ((ObjectNode) candidate.path("profileMeta")).put("displayName", "Shared Site Profile Updated");
    ((ObjectNode) candidate.path("configDefaults")).put("aggregationMode", "BY_SPECIMEN");
    catalog.updateDraft(firstSuccessor.draftId(), candidate, "profile-editor");

    ProfileRevision revisionTwo = catalog.publishDraft(firstSuccessor.draftId(), "profile-publisher");
    assertThat(revisionTwo.profile().path("catalog").path("revision").asInt()).isEqualTo(2);
    assertThat(revisionTwo.publication().action()).isEqualTo(ProfileAuditAction.UPDATED);
    assertThat(catalog.require(profileId, 1)).isEqualTo(revisionOne);
    assertThat(catalog.require(profileId, 1).profile().path("profileMeta").path("displayName").asText()).isEqualTo(
      "Shared Site Profile"
    );
    assertThat(catalog.require(profileId, 2).profile().path("profileMeta").path("displayName").asText()).isEqualTo(
      "Shared Site Profile Updated"
    );
    assertThatThrownBy(() -> catalog.publishDraft(staleSuccessor.draftId(), "second-publisher"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("newer revision");
    assertThat(catalog.requireDraft(staleSuccessor.draftId())).isEqualTo(staleSuccessor);
  }

  @Test
  void deactivateAndReactivateAppendAuditRevisionsWithoutChangingProfileBehavior() throws Exception {
    var duplicate = catalog.duplicateDraft("genexpert-astm", 1, "Lifecycle Site Profile", "profile-duplicator");
    ProfileRevision revisionOne = catalog.publishDraft(duplicate.draftId(), "profile-publisher");
    String profileId = revisionOne.profile().path("profileMeta").path("id").asText();

    ProfileRevision inactive = catalog.deactivate(profileId, "profile-deactivator");
    assertThat(inactive.profile().path("catalog").path("revision").asInt()).isEqualTo(2);
    assertThat(inactive.profile().path("catalog").path("status").asText()).isEqualTo("INACTIVE");
    assertThat(inactive.profile().path("catalog").path("source").asText()).isEqualTo("SITE");
    assertThat(inactive.profile().path("protocol")).isEqualTo(revisionOne.profile().path("protocol"));
    assertThat(inactive.profile().path("configDefaults")).isEqualTo(revisionOne.profile().path("configDefaults"));
    assertThat(inactive.profile().path("default_test_mappings")).isEqualTo(
      revisionOne.profile().path("default_test_mappings")
    );
    assertThat(inactive.publication().action()).isEqualTo(ProfileAuditAction.DEACTIVATED);
    assertThat(catalog.require(profileId, 1)).isEqualTo(revisionOne);
    assertThat(catalog.require(profileId, 1).profile().path("catalog").path("status").asText()).isEqualTo("ACTIVE");
    assertThatThrownBy(() -> catalog.duplicateDraft(profileId, 2, "Inactive Profile Copy", "profile-duplicator"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("inactive");

    ProfileRevision reactivated = catalog.reactivate(profileId, "profile-reactivator");
    assertThat(reactivated.profile().path("catalog").path("revision").asInt()).isEqualTo(3);
    assertThat(reactivated.profile().path("catalog").path("status").asText()).isEqualTo("ACTIVE");
    assertThat(reactivated.publication().action()).isEqualTo(ProfileAuditAction.REACTIVATED);
    assertThatThrownBy(() -> catalog.reactivate(profileId, "profile-reactivator"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("already active");

    AnalyzerProfileCatalog reopened = new AnalyzerProfileCatalog(
      catalogDirectory,
      shippedProfiles,
      objectMapper,
      Clock.fixed(NOW, ZoneOffset.UTC)
    );
    assertThat(reopened.history(profileId))
      .extracting(revision -> revision.publication().action())
      .containsExactly(ProfileAuditAction.DUPLICATED, ProfileAuditAction.DEACTIVATED, ProfileAuditAction.REACTIVATED);
  }

  @Test
  void shippedProfilesCanBeLocallyDeactivatedWithoutBecomingSiteEditable() {
    ObjectNode shippedRevision = catalog.require("genexpert-astm", 1).profile();

    ProfileRevision inactive = catalog.deactivate("genexpert-astm", "profile-deactivator");
    assertThat(inactive.profile().path("catalog").path("revision").asInt()).isEqualTo(2);
    assertThat(inactive.profile().path("catalog").path("source").asText()).isEqualTo("SHIPPED");
    assertThat(inactive.profile().path("catalog").path("status").asText()).isEqualTo("INACTIVE");
    assertThat(catalog.require("genexpert-astm", 1).profile()).isEqualTo(shippedRevision);
    assertThatThrownBy(() -> catalog.updateSharedDraft("genexpert-astm", 2, "profile-editor"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("Duplicate Profile");

    AnalyzerProfileCatalog reopened = new AnalyzerProfileCatalog(
      catalogDirectory,
      shippedProfiles,
      objectMapper,
      Clock.fixed(NOW, ZoneOffset.UTC)
    );
    assertThat(reopened.require("genexpert-astm", 2)).isEqualTo(inactive);
  }

  @Test
  void incompleteOrClientCatalogAuthoredDraftCannotPublishAndRemainsEditable() throws Exception {
    var draft = catalog.createDraft("Incomplete Site Profile", "profile-creator");

    assertThatThrownBy(() -> catalog.publishDraft(draft.draftId(), "profile-publisher"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("cannot be published");
    assertThat(catalog.requireDraft(draft.draftId())).isEqualTo(draft);

    ObjectNode clientOwnedCatalog = authoredFixture(
      "analyzer-profile-file.json",
      draft.profile().path("profileMeta").path("id").asText(),
      "Incomplete Site Profile"
    );
    clientOwnedCatalog.set("catalog", publishedFixture("analyzer-profile-file.json").path("catalog").deepCopy());

    assertThatThrownBy(() -> catalog.updateDraft(draft.draftId(), clientOwnedCatalog, "profile-editor"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("catalog metadata");
    assertThat(catalog.requireDraft(draft.draftId())).isEqualTo(draft);
  }

  @Test
  void loadsCompleteEstablishedProfilesWithoutTranslatingTheirRuntimeOrDefaults() {
    assertThat(catalog.latest())
      .extracting(revision -> revision.profile().path("profileMeta").path("id").asText())
      .containsExactly("fluorocycler-xt", "genexpert-astm");

    ObjectNode astm = catalog.require("genexpert-astm", 1).profile();
    assertThat(astm.path("protocol").path("name").asText()).isEqualTo("ASTM");
    assertThat(astm.path("identifier_pattern").asText()).isNotBlank();
    assertThat(astm.path("transport_config").isObject()).isTrue();
    assertThat(astm.path("configDefaults").path("connectionRole").asText()).isNotBlank();
    assertThat(astm.path("default_test_mappings")).isNotEmpty();

    ObjectNode file = catalog.require("fluorocycler-xt", 1).profile();
    assertThat(file.path("protocol").path("name").asText()).isEqualTo("FILE");
    assertThat(file.path("supported_extensions")).isNotEmpty();
    assertThat(file.path("column_mapping").isObject()).isTrue();
    assertThat(file.path("configDefaults").path("filePattern").asText()).isNotBlank();
    assertThat(file.path("default_test_mappings")).isNotEmpty();
  }

  @Test
  void keepsPublishedRevisionsImmutableAndReturnsDefensiveCopies() {
    ProfileRevision original = catalog.require("genexpert-astm", 1);
    ObjectNode returned = original.profile();
    ((ObjectNode) returned.path("profileMeta")).put("displayName", "Changed by caller");

    assertThat(
      catalog.require("genexpert-astm", 1).profile().path("profileMeta").path("displayName").asText()
    ).isNotEqualTo("Changed by caller");
    assertThat(catalog.history("genexpert-astm")).containsExactly(original);
  }

  @Test
  void validatesNestedRecognitionAndRevisionFingerprints() throws Exception {
    ObjectNode tampered = publishedFixture("analyzer-profile-file.json");
    ((ObjectNode) tampered.path("profileMeta")).put("displayName", "Changed after publication");

    assertThatThrownBy(
      () ->
        new AnalyzerProfileCatalog(
          catalogDirectory,
          List.of(resource(tampered, "tampered.json")),
          objectMapper,
          Clock.fixed(NOW, ZoneOffset.UTC)
        )
    )
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("revision fingerprint mismatch");
  }

  @Test
  void catalogFingerprintIsDeterministicAcrossResourceOrderAndRestart() {
    AnalyzerProfileCatalog reverseOrder = new AnalyzerProfileCatalog(
      catalogDirectory,
      shippedProfiles.reversed(),
      objectMapper,
      Clock.fixed(NOW, ZoneOffset.UTC)
    );

    assertThat(reverseOrder.catalogFingerprint()).isEqualTo(catalog.catalogFingerprint());
  }

  @Test
  void profileProductionCodeContainsNoParallelPortableProfilePath() throws Exception {
    try (Stream<Path> files = Files.walk(Path.of("src", "main", "java", "org", "itech", "ahb", "profile"))) {
      String source = files
        .filter(Files::isRegularFile)
        .map(path -> {
          try {
            return Files.readString(path, StandardCharsets.UTF_8);
          } catch (Exception exception) {
            throw new IllegalStateException(exception);
          }
        })
        .reduce("", String::concat);

      assertThat(source).doesNotContain("PortableProfile").doesNotContain("portable-profile.schema.json");
    }
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
    return resource(profile, profile.path("profileMeta").path("id").asText() + ".json");
  }

  private Resource resource(ObjectNode profile, String filename) throws Exception {
    byte[] content = objectMapper.writeValueAsBytes(profile);
    return new ByteArrayResource(content) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
  }
}
