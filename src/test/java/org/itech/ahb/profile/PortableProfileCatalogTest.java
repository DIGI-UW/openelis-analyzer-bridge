package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class PortableProfileCatalogTest {

  private static final Instant NOW = Instant.parse("2026-08-14T02:00:00Z");

  @TempDir
  Path catalogDirectory;

  private ObjectMapper objectMapper;
  private Clock clock;
  private List<Resource> shippedProfiles;
  private PortableProfileCatalog catalog;

  @BeforeEach
  void setUp() throws Exception {
    objectMapper = new ObjectMapper();
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    shippedProfiles = List.of(resource(profile("genexpert-astm", "GeneXpert ASTM", "SHIPPED")));
    catalog = new PortableProfileCatalog(catalogDirectory, shippedProfiles, objectMapper, clock);
  }

  @Test
  void listsLatestShippedAndSiteProfilesAndPersistsSiteCreationAcrossRestart() throws Exception {
    ObjectNode candidate = (ObjectNode) profile("site-chemistry", "Site Chemistry", "SHIPPED");
    candidate.put("revision", 44);
    ProfileCatalogEntry created = catalog.createSite(candidate, "oe-user-17");

    assertThat(created.profile().path("source").asText()).isEqualTo("SITE");
    assertThat(created.profile().path("revision").asInt()).isEqualTo(1);
    assertThat(created.profile().path("status").asText()).isEqualTo("ACTIVE");
    assertThat(created.audit().action()).isEqualTo(ProfileAuditAction.CREATED);
    assertThat(created.audit().actor()).isEqualTo("oe-user-17");
    assertThat(created.audit().markedAt()).isEqualTo(NOW);
    assertThat(created.fingerprint()).startsWith("sha256:");

    PortableProfileCatalog reopened = new PortableProfileCatalog(
      catalogDirectory,
      shippedProfiles,
      objectMapper,
      clock
    );

    assertThat(reopened.list(ProfileCatalogFilter.all()))
      .extracting(entry -> entry.profile().path("profileId").asText())
      .containsExactly("genexpert-astm", "site-chemistry");
    assertThat(reopened.require("site-chemistry", 1)).isEqualTo(created);
  }

  @Test
  void rejectsDuplicateProfileIdsAndDisplayNamesCaseInsensitively() throws Exception {
    catalog.createSite(profile("site-chemistry", "Site Chemistry", "SHIPPED"), "creator");

    assertThatThrownBy(() -> catalog.createSite(profile("site-chemistry", "Another name", "SITE"), "creator"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("profileId");
    assertThatThrownBy(() -> catalog.createSite(profile("another-id", " site chemistry ", "SITE"), "creator"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("displayName");
  }

  @Test
  void forksAnActiveRevisionWithDurableLineageAndAUniqueIdentity() throws Exception {
    ProfileCatalogEntry fork = catalog.fork("genexpert-astm", 1, "site-genexpert", "Site GeneXpert", "oe-admin");

    assertThat(fork.profile().path("profileId").asText()).isEqualTo("site-genexpert");
    assertThat(fork.profile().path("displayName").asText()).isEqualTo("Site GeneXpert");
    assertThat(fork.profile().path("source").asText()).isEqualTo("SITE");
    assertThat(fork.profile().path("revision").asInt()).isEqualTo(1);
    assertThat(fork.profile().path("lineage").path("parentProfileId").asText()).isEqualTo("genexpert-astm");
    assertThat(fork.profile().path("lineage").path("parentRevision").asInt()).isEqualTo(1);
    assertThat(fork.audit().action()).isEqualTo(ProfileAuditAction.FORKED);

    assertThat(catalog.require("genexpert-astm", 1).profile().path("lineage").isMissingNode()).isTrue();
  }

  @Test
  void requiresForkBeforeEditingShippedProfileContent() throws Exception {
    ObjectNode edited = (ObjectNode) profile("genexpert-astm", "Edited shipped profile", "SHIPPED");

    assertThatThrownBy(() -> catalog.revise("genexpert-astm", edited, "oe-admin"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("forked");

    assertThat(catalog.history("genexpert-astm"))
      .extracting(entry -> entry.profile().path("revision").asInt())
      .containsExactly(1);
  }

  @Test
  void appendsAuditedLifecycleRevisionsWithoutDeletingHistory() throws Exception {
    catalog.createSite(profile("site-chemistry", "Site Chemistry", "SITE"), "creator");

    ProfileCatalogEntry inactive = catalog.deactivate("site-chemistry", "reviewer");
    ProfileCatalogEntry active = catalog.reactivate("site-chemistry", "reviewer");

    assertThat(inactive.profile().path("revision").asInt()).isEqualTo(2);
    assertThat(inactive.profile().path("status").asText()).isEqualTo("INACTIVE");
    assertThat(inactive.audit().action()).isEqualTo(ProfileAuditAction.DEACTIVATED);
    assertThat(active.profile().path("revision").asInt()).isEqualTo(3);
    assertThat(active.profile().path("status").asText()).isEqualTo("ACTIVE");
    assertThat(active.audit().action()).isEqualTo(ProfileAuditAction.REACTIVATED);

    assertThat(catalog.history("site-chemistry"))
      .extracting(entry -> entry.profile().path("revision").asInt())
      .containsExactly(1, 2, 3);
    assertThat(catalog.require("site-chemistry", 1).profile().path("status").asText()).isEqualTo("ACTIVE");
  }

  @Test
  void preventsNewUseOfInactiveProfilesAndRejectsNoOpLifecycleActions() throws Exception {
    catalog.createSite(profile("site-chemistry", "Site Chemistry", "SITE"), "creator");
    catalog.deactivate("site-chemistry", "reviewer");

    assertThatThrownBy(() -> catalog.fork("site-chemistry", 2, "blocked-fork", "Blocked fork", "creator"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("inactive");
    assertThatThrownBy(() -> catalog.deactivate("site-chemistry", "reviewer"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("already inactive");
  }

  @Test
  void rejectsOpenElisOnlyFieldsThroughThePublishedSchema() throws Exception {
    ObjectNode candidate = (ObjectNode) profile("invalid-local-id", "Invalid local ID", "SITE");
    candidate.put("openelisTestId", "42");

    assertThatThrownBy(() -> catalog.createSite(candidate, "creator"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("openelisTestId");
  }

  @Test
  void rejectsIncompleteCapabilitiesThroughThePublishedSchema() throws Exception {
    ObjectNode candidate = (ObjectNode) profile("invalid-capabilities", "Invalid capabilities", "SITE");
    ((ObjectNode) candidate.path("capabilities")).remove("outboundOrders");

    assertThatThrownBy(() -> catalog.createSite(candidate, "creator"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("outboundOrders");
  }

  @Test
  void rejectsFileProfilesWithoutAFileDefinitionThroughThePublishedSchema() throws Exception {
    ObjectNode candidate = (ObjectNode) profile("invalid-file", "Invalid FILE profile", "SITE");
    candidate.put("protocol", "FILE");

    assertThatThrownBy(() -> catalog.createSite(candidate, "creator"))
      .isInstanceOf(ProfileCatalogException.class)
      .hasMessageContaining("file");
  }

  private Resource resource(JsonNode profile) throws Exception {
    return new ByteArrayResource(objectMapper.writeValueAsBytes(profile)) {
      @Override
      public String getFilename() {
        return profile.path("profileId").asText() + ".json";
      }
    };
  }

  private JsonNode profile(String profileId, String displayName, String source) throws Exception {
    return objectMapper.readTree(
      """
      {
        "schemaVersion": "1.0",
        "profileId": "%s",
        "revision": 1,
        "displayName": "%s",
        "source": "%s",
        "status": "ACTIVE",
        "protocol": "ASTM",
        "capabilities": {
          "inboundResults": true,
          "outboundOrders": false,
          "connectionTest": true
        },
        "tests": [],
        "qcIdentification": []
      }
      """.formatted(profileId, displayName, source)
    );
  }
}
