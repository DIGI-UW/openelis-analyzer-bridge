package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.core.io.Resource;

/** Catalog of editable drafts and immutable revisions using the established profile contract. */
public final class AnalyzerProfileCatalog {

  private static final String PROFILE_SCHEMA =
    "https://openelis-global.org/contracts/analyzer/v1/analyzer-profile.schema.json";
  private static final String PROFILE_SCHEMA_VERSION = "1.0";
  private static final String EMPTY_FINGERPRINT = "sha256:" + "0".repeat(64);

  private static final Comparator<ProfileRevision> BY_DISPLAY_NAME = Comparator.comparing(
    revision -> revision.profile().path("profileMeta").path("displayName").asText(),
    String.CASE_INSENSITIVE_ORDER
  );
  private static final Comparator<ProfileDraft> DRAFT_BY_DISPLAY_NAME = Comparator.comparing(
    draft -> draft.profile().path("profileMeta").path("displayName").asText(),
    String.CASE_INSENSITIVE_ORDER
  );

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Supplier<UUID> ids;
  private final ProfileCatalogFileStore store;
  private final ProfileFingerprintService fingerprints = new ProfileFingerprintService();
  private final AnalyzerProfileValidator validator;
  private final ControlRecognitionAuthoring controlRecognitionAuthoring;
  private final Map<String, TreeMap<Integer, ProfileRevision>> revisions = new TreeMap<>();
  private final Map<String, ProfileDraft> drafts = new TreeMap<>();

  public AnalyzerProfileCatalog(
    Path catalogDirectory,
    List<Resource> shippedProfiles,
    ObjectMapper objectMapper,
    Clock clock
  ) {
    this(catalogDirectory, shippedProfiles, objectMapper, clock, UUID::randomUUID);
  }

  AnalyzerProfileCatalog(
    Path catalogDirectory,
    List<Resource> shippedProfiles,
    ObjectMapper objectMapper,
    Clock clock,
    Supplier<UUID> ids
  ) {
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.ids = ids;
    store = new ProfileCatalogFileStore(catalogDirectory, objectMapper);
    validator = new AnalyzerProfileValidator(objectMapper);
    controlRecognitionAuthoring = new ControlRecognitionAuthoring(objectMapper);
    loadShipped(shippedProfiles);
    loadPersistedRevisions();
    loadPersistedDrafts();
    validateUniqueLatestDisplayNames();
  }

  public synchronized ProfileDraft createDraft(String displayName, String actor) {
    String normalizedName = requireText(displayName, "displayName");
    String normalizedActor = requireText(actor, "actor");
    requireAvailableDisplayName(normalizedName, null, null);

    String profileId = nextProfileId();
    ObjectNode profile = objectMapper.createObjectNode();
    profile.put("$schema", PROFILE_SCHEMA);
    profile.put("schemaVersion", PROFILE_SCHEMA_VERSION);
    ObjectNode profileMeta = profile.putObject("profileMeta");
    profileMeta.put("id", profileId);
    profileMeta.put("displayName", normalizedName);
    return addDraft(ProfileDraftKind.CREATE, profile, null, null, normalizedActor);
  }

  public synchronized ProfileDraft duplicateDraft(
    String sourceProfileId,
    int sourceRevision,
    String displayName,
    String actor
  ) {
    ProfileRevision source = require(sourceProfileId, sourceRevision);
    if (!"ACTIVE".equals(requireLatest(sourceProfileId).profile().path("catalog").path("status").asText())) {
      throw new ProfileCatalogException("Cannot duplicate an inactive profile");
    }

    String normalizedName = requireText(displayName, "displayName");
    String normalizedActor = requireText(actor, "actor");
    requireAvailableDisplayName(normalizedName, null, null);
    String profileId = nextProfileId();
    ObjectNode profile = authoredCopy(source.profile());
    ObjectNode profileMeta = requireObject(profile.path("profileMeta"), "profileMeta");
    profileMeta.put("id", profileId);
    profileMeta.put("displayName", normalizedName);
    return addDraft(ProfileDraftKind.DUPLICATE, profile, sourceProfileId, sourceRevision, normalizedActor);
  }

  public synchronized ProfileDraft updateSharedDraft(String profileId, int sourceRevision, String actor) {
    ProfileRevision source = require(profileId, sourceRevision);
    ObjectNode sourceProfile = source.profile();
    if (!"SITE".equals(sourceProfile.path("catalog").path("source").asText())) {
      throw new ProfileCatalogException("Shipped profiles are immutable; use Duplicate Profile first");
    }
    ProfileRevision latest = requireLatest(profileId);
    int latestRevision = latest.profile().path("catalog").path("revision").asInt();
    if (latestRevision != sourceRevision) {
      throw new ProfileCatalogException("Update shared must start from the latest profile revision");
    }
    if (!"ACTIVE".equals(latest.profile().path("catalog").path("status").asText())) {
      throw new ProfileCatalogException("Cannot update an inactive profile");
    }
    return addDraft(
      ProfileDraftKind.UPDATE,
      authoredCopy(sourceProfile),
      profileId,
      sourceRevision,
      requireText(actor, "actor")
    );
  }

  public synchronized ProfileDraft updateDraft(String draftId, ObjectNode candidate, String actor) {
    ProfileDraft current = requireDraft(draftId);
    if (candidate == null) {
      throw new ProfileCatalogException("profile is required");
    }
    if (candidate.has("catalog")) {
      throw new ProfileCatalogException("catalog metadata is generated by Bridge and cannot be authored");
    }

    String normalizedActor = requireText(actor, "actor");
    ObjectNode profile = candidate.deepCopy();
    profile.put("$schema", PROFILE_SCHEMA);
    profile.put("schemaVersion", PROFILE_SCHEMA_VERSION);
    ObjectNode profileMeta = profile.withObject("profileMeta");
    String profileId = current.profile().path("profileMeta").path("id").asText();
    profileMeta.put("id", profileId);
    if (!profileMeta.has("displayName")) {
      profileMeta.set("displayName", current.profile().path("profileMeta").path("displayName").deepCopy());
    }
    String displayName = requireText(profileMeta.path("displayName").asText(null), "displayName");
    profileMeta.put("displayName", displayName);
    String sameProfileId = current.kind() == ProfileDraftKind.UPDATE ? profileId : null;
    requireAvailableDisplayName(displayName, sameProfileId, draftId);

    Instant updatedAt = clock.instant();
    ProfileDraft changed = current.withProfile(profile, normalizedActor, updatedAt, List.of());
    changed = changed.withValidationIssues(validationIssues(changed));
    store.persistDraft(changed);
    drafts.put(draftId, changed);
    return changed;
  }

  public synchronized ControlRecognitionAuthoring.DraftView inspectControlRecognition(String draftId) {
    return controlRecognitionAuthoring.inspect(requireDraft(draftId));
  }

  public synchronized ControlRecognitionAuthoring.DraftView updateControlRecognition(
    String draftId,
    ControlRecognitionAuthoring.Update update,
    String actor
  ) {
    ProfileDraft current = requireDraft(draftId);
    ObjectNode changedProfile = controlRecognitionAuthoring.apply(current.profile(), update);
    return controlRecognitionAuthoring.inspect(updateDraft(draftId, changedProfile, actor));
  }

  public synchronized ProfileRevision publishDraft(String draftId, String actor) {
    ProfileDraft draft = requireDraft(draftId);
    String normalizedActor = requireText(actor, "actor");
    String profileId = draft.profile().path("profileMeta").path("id").asText();
    if (draft.kind() == ProfileDraftKind.UPDATE) {
      int latestRevision = requireLatest(profileId).profile().path("catalog").path("revision").asInt();
      if (latestRevision != draft.baseRevision()) {
        throw new ProfileCatalogException("A newer revision exists; refresh the Update shared draft");
      }
    } else if (revisions.containsKey(profileId)) {
      throw new ProfileCatalogException("Profile identity already exists: " + profileId);
    }

    String displayName = requireText(
      draft.profile().path("profileMeta").path("displayName").asText(null),
      "displayName"
    );
    requireAvailableDisplayName(displayName, draft.kind() == ProfileDraftKind.UPDATE ? profileId : null, draftId);

    Instant publishedAt = clock.instant();
    ObjectNode profile = publicationCandidate(draft, normalizedActor, publishedAt);
    List<String> issues = validator.validationIssues(profile);
    if (!issues.isEmpty()) {
      throw new ProfileCatalogException("Profile draft cannot be published: " + String.join("; ", issues));
    }
    ObjectNode catalog = requireObject(profile.path("catalog"), "catalog");
    catalog.put(
      "recognitionFingerprint",
      fingerprints.recognitionFingerprint(profile.path("controlResultRecognition"))
    );
    catalog.put("revisionFingerprint", fingerprints.revisionFingerprint(profile));
    validator.validate(profile);

    ProfileRevision revision = new ProfileRevision(
      profile,
      new ProfilePublication(publicationAction(draft.kind()), normalizedActor, publishedAt)
    );
    Path revisionPath = store.persistRevision(revision);
    try {
      store.deleteDraft(draftId);
    } catch (RuntimeException exception) {
      store.rollbackRevision(revisionPath, exception);
      throw exception;
    }
    add(revision);
    drafts.remove(draftId);
    return revision;
  }

  public synchronized ProfileRevision deactivate(String profileId, String actor) {
    return changeStatus(profileId, "INACTIVE", ProfileAuditAction.DEACTIVATED, actor);
  }

  public synchronized ProfileRevision reactivate(String profileId, String actor) {
    return changeStatus(profileId, "ACTIVE", ProfileAuditAction.REACTIVATED, actor);
  }

  public synchronized ProfileDraft requireDraft(String draftId) {
    ProfileDraft draft = drafts.get(draftId);
    if (draft == null) {
      throw new ProfileCatalogException("Unknown profile draft: " + draftId);
    }
    return draft;
  }

  public synchronized List<ProfileDraft> drafts() {
    return drafts.values().stream().sorted(DRAFT_BY_DISPLAY_NAME).toList();
  }

  public synchronized ProfileRevision require(String profileId, int revision) {
    TreeMap<Integer, ProfileRevision> history = revisions.get(profileId);
    if (history == null || !history.containsKey(revision)) {
      throw new ProfileCatalogException("Unknown profile revision: " + profileId + "@" + revision);
    }
    return history.get(revision);
  }

  public synchronized ProfileRevision requireLatest(String profileId) {
    TreeMap<Integer, ProfileRevision> history = revisions.get(profileId);
    if (history == null || history.isEmpty()) {
      throw new ProfileCatalogException("Unknown profile: " + profileId);
    }
    return history.lastEntry().getValue();
  }

  public synchronized List<ProfileRevision> history(String profileId) {
    TreeMap<Integer, ProfileRevision> history = revisions.get(profileId);
    return history == null ? List.of() : List.copyOf(history.values());
  }

  public synchronized List<ProfileRevision> latest() {
    return revisions.values().stream().map(history -> history.lastEntry().getValue()).sorted(BY_DISPLAY_NAME).toList();
  }

  public synchronized String catalogFingerprint() {
    ObjectNode state = objectMapper.createObjectNode();
    state.put("schemaVersion", PROFILE_SCHEMA_VERSION);
    ArrayNode profiles = state.putArray("profiles");
    revisions
      .entrySet()
      .stream()
      .sorted(Map.Entry.comparingByKey())
      .map(entry -> entry.getValue().lastEntry().getValue().profile())
      .forEach(profiles::add);
    return fingerprints.canonicalFingerprint(state);
  }

  private ProfileDraft addDraft(
    ProfileDraftKind kind,
    ObjectNode profile,
    String baseProfileId,
    Integer baseRevision,
    String actor
  ) {
    String draftId = nextDraftId();
    Instant markedAt = clock.instant();
    ProfileDraft draft = new ProfileDraft(
      draftId,
      kind,
      profile,
      baseProfileId,
      baseRevision,
      actor,
      markedAt,
      actor,
      markedAt,
      List.of()
    );
    draft = draft.withValidationIssues(validationIssues(draft));
    store.persistDraft(draft);
    drafts.put(draftId, draft);
    return draft;
  }

  private ProfileRevision changeStatus(String profileId, String targetStatus, ProfileAuditAction action, String actor) {
    ProfileRevision current = requireLatest(profileId);
    ObjectNode profile = current.profile();
    ObjectNode catalog = requireObject(profile.path("catalog"), "catalog");
    String currentStatus = catalog.path("status").asText();
    if (targetStatus.equals(currentStatus)) {
      throw new ProfileCatalogException("Profile is already " + targetStatus.toLowerCase(Locale.ROOT));
    }

    String normalizedActor = requireText(actor, "actor");
    Instant markedAt = clock.instant();
    catalog.put("revision", catalog.path("revision").asInt() + 1);
    catalog.put("status", targetStatus);
    catalog.put("publishedAt", markedAt.toString());
    catalog.put("publishedBy", normalizedActor);
    catalog.put(
      "recognitionFingerprint",
      fingerprints.recognitionFingerprint(profile.path("controlResultRecognition"))
    );
    catalog.put("revisionFingerprint", fingerprints.revisionFingerprint(profile));
    validator.validate(profile);

    ProfileRevision statusRevision = new ProfileRevision(
      profile,
      new ProfilePublication(action, normalizedActor, markedAt)
    );
    store.persistRevision(statusRevision);
    add(statusRevision);
    return statusRevision;
  }

  private List<String> validationIssues(ProfileDraft draft) {
    return validator.validationIssues(publicationCandidate(draft, draft.updatedBy(), draft.updatedAt()));
  }

  private ObjectNode publicationCandidate(ProfileDraft draft, String actor, Instant publishedAt) {
    ObjectNode profile = draft.profile();
    profile.put("$schema", PROFILE_SCHEMA);
    profile.put("schemaVersion", PROFILE_SCHEMA_VERSION);
    ObjectNode profileMeta = profile.withObject("profileMeta");
    String profileId = draft.profile().path("profileMeta").path("id").asText();
    profileMeta.put("id", profileId);

    ObjectNode catalog = profile.putObject("catalog");
    int revision = draft.kind() == ProfileDraftKind.UPDATE ? draft.baseRevision() + 1 : 1;
    catalog.put("revision", revision);
    catalog.put("revisionFingerprint", EMPTY_FINGERPRINT);
    catalog.put("recognitionFingerprint", EMPTY_FINGERPRINT);
    catalog.put("source", "SITE");
    catalog.put("status", "ACTIVE");
    catalog.put("publishedAt", publishedAt.toString());
    catalog.put("publishedBy", actor);
    if (draft.kind() == ProfileDraftKind.DUPLICATE) {
      ObjectNode lineage = catalog.putObject("lineage");
      lineage.put("parentProfileId", draft.baseProfileId());
      lineage.put("parentRevision", draft.baseRevision());
    } else if (draft.kind() == ProfileDraftKind.UPDATE) {
      JsonNode lineage = require(draft.baseProfileId(), draft.baseRevision()).profile().path("catalog").path("lineage");
      if (lineage.isObject()) {
        catalog.set("lineage", lineage.deepCopy());
      }
    }
    return profile;
  }

  private void loadShipped(List<Resource> shippedProfiles) {
    for (Resource resource : shippedProfiles) {
      try {
        ObjectNode profile = requireObject(
          objectMapper.readTree(resource.getInputStream()),
          "Shipped profile " + resource.getDescription()
        );
        JsonNode metadata = profile.path("catalog");
        if (!"SHIPPED".equals(metadata.path("source").asText())) {
          throw new ProfileCatalogException("Shipped profile must declare source SHIPPED: " + resource.getFilename());
        }
        validatePublished(profile, "Shipped profile " + resource.getFilename());
        add(
          new ProfileRevision(
            profile,
            new ProfilePublication(
              ProfileAuditAction.SHIPPED,
              metadata.path("publishedBy").asText(),
              Instant.parse(metadata.path("publishedAt").asText())
            )
          )
        );
      } catch (IOException | IllegalArgumentException exception) {
        throw new ProfileCatalogException("Cannot load shipped profile " + resource.getDescription(), exception);
      }
    }
  }

  private void loadPersistedRevisions() {
    store.revisionDocuments().forEach(this::loadPersistedRevision);
  }

  private void loadPersistedRevision(ProfileCatalogFileStore.StoredDocument stored) {
    Path path = stored.path();
    ObjectNode envelope = stored.document();
    ObjectNode profile = requireObject(envelope.path("profile"), "Persisted profile payload " + path);
    ObjectNode publicationNode = requireObject(envelope.path("publication"), "Profile publication " + path);
    ProfilePublication publication = new ProfilePublication(
      parseAction(publicationNode.path("action").asText(null), path),
      requireText(publicationNode.path("actor").asText(null), "publication actor"),
      parseInstant(publicationNode.path("markedAt").asText(null), path)
    );
    validatePersistedOrigin(profile, publication.action(), path);
    validatePublished(profile, "Persisted profile " + path);
    add(new ProfileRevision(profile, publication));
  }

  private void loadPersistedDrafts() {
    store.draftDocuments().forEach(this::loadDraft);
  }

  private void loadDraft(ProfileCatalogFileStore.StoredDocument stored) {
    Path path = stored.path();
    try {
      ObjectNode document = stored.document();
      String draftId = requireText(document.path("draftId").asText(null), "draftId");
      UUID.fromString(draftId);
      ObjectNode profile = requireObject(document.path("profile"), "Persisted draft profile " + path);
      if (profile.has("catalog")) {
        throw new ProfileCatalogException("Persisted draft cannot contain catalog metadata: " + path);
      }
      ProfileDraft draft = new ProfileDraft(
        draftId,
        ProfileDraftKind.valueOf(requireText(document.path("kind").asText(null), "draft kind")),
        profile,
        nullableText(document.path("baseProfileId")),
        nullableInteger(document.path("baseRevision")),
        requireText(document.path("createdBy").asText(null), "draft createdBy"),
        parseInstant(document.path("createdAt").asText(null), path),
        requireText(document.path("updatedBy").asText(null), "draft updatedBy"),
        parseInstant(document.path("updatedAt").asText(null), path),
        List.of()
      );
      draft = draft.withValidationIssues(validationIssues(draft));
      if (drafts.putIfAbsent(draftId, draft) != null) {
        throw new ProfileCatalogException("Duplicate profile draft: " + draftId);
      }
    } catch (IllegalArgumentException exception) {
      throw new ProfileCatalogException("Cannot load persisted profile draft " + path, exception);
    }
  }

  private void validatePublished(ObjectNode profile, String description) {
    validator.validate(profile);
    JsonNode metadata = profile.path("catalog");
    String expectedRecognition = fingerprints.recognitionFingerprint(profile.path("controlResultRecognition"));
    if (!expectedRecognition.equals(metadata.path("recognitionFingerprint").asText())) {
      throw new ProfileCatalogException(description + " recognition fingerprint mismatch");
    }
    String expectedRevision = fingerprints.revisionFingerprint(profile);
    if (!expectedRevision.equals(metadata.path("revisionFingerprint").asText())) {
      throw new ProfileCatalogException(description + " revision fingerprint mismatch");
    }
  }

  private static void validatePersistedOrigin(ObjectNode profile, ProfileAuditAction action, Path path) {
    String source = profile.path("catalog").path("source").asText();
    if ("SITE".equals(source) && action != ProfileAuditAction.SHIPPED) {
      return;
    }
    if (
      "SHIPPED".equals(source) && (action == ProfileAuditAction.DEACTIVATED || action == ProfileAuditAction.REACTIVATED)
    ) {
      return;
    }
    throw new ProfileCatalogException("Invalid persisted profile source/action in " + path);
  }

  private void requireAvailableDisplayName(String displayName, String sameProfileId, String excludedDraftId) {
    String key = identityKey(displayName);
    boolean publishedDuplicate = latest()
      .stream()
      .filter(revision -> !revision.profile().path("profileMeta").path("id").asText().equals(sameProfileId))
      .map(revision -> identityKey(revision.profile().path("profileMeta").path("displayName").asText()))
      .anyMatch(key::equals);
    boolean draftDuplicate = drafts
      .values()
      .stream()
      .filter(draft -> !draft.draftId().equals(excludedDraftId))
      .filter(draft -> !draft.profile().path("profileMeta").path("id").asText().equals(sameProfileId))
      .map(draft -> identityKey(draft.profile().path("profileMeta").path("displayName").asText()))
      .anyMatch(key::equals);
    if (publishedDuplicate || draftDuplicate) {
      throw new ProfileCatalogException("displayName already exists: " + displayName);
    }
  }

  private void validateUniqueLatestDisplayNames() {
    List<String> names = latest()
      .stream()
      .map(revision -> identityKey(revision.profile().path("profileMeta").path("displayName").asText()))
      .toList();
    if (names.stream().distinct().count() != names.size()) {
      throw new ProfileCatalogException("Profile catalog contains duplicate displayName values");
    }
  }

  private void add(ProfileRevision revision) {
    ObjectNode profile = revision.profile();
    String profileId = profile.path("profileMeta").path("id").asText();
    int revisionNumber = profile.path("catalog").path("revision").asInt();
    TreeMap<Integer, ProfileRevision> history = revisions.computeIfAbsent(profileId, ignored -> new TreeMap<>());
    if (history.putIfAbsent(revisionNumber, revision) != null) {
      throw new ProfileCatalogException("Duplicate profile revision: " + profileId + "@" + revisionNumber);
    }
  }

  private String nextProfileId() {
    String profileId = "site." + ids.get().toString();
    boolean draftCollision = drafts
      .values()
      .stream()
      .anyMatch(draft -> profileId.equals(draft.profile().path("profileMeta").path("id").asText()));
    if (revisions.containsKey(profileId) || draftCollision) {
      throw new ProfileCatalogException("Generated profile identity collision");
    }
    return profileId;
  }

  private String nextDraftId() {
    String draftId = ids.get().toString();
    if (drafts.containsKey(draftId)) {
      throw new ProfileCatalogException("Generated profile draft identity collision");
    }
    return draftId;
  }

  private static ObjectNode authoredCopy(ObjectNode publishedProfile) {
    ObjectNode profile = publishedProfile.deepCopy();
    profile.remove("catalog");
    return profile;
  }

  private static ProfileAuditAction publicationAction(ProfileDraftKind kind) {
    return switch (kind) {
      case CREATE -> ProfileAuditAction.CREATED;
      case DUPLICATE -> ProfileAuditAction.DUPLICATED;
      case UPDATE -> ProfileAuditAction.UPDATED;
    };
  }

  static ObjectNode requireObject(JsonNode node, String description) {
    if (!(node instanceof ObjectNode object)) {
      throw new ProfileCatalogException(description + " must be a JSON object");
    }
    return object;
  }

  static String requireText(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new ProfileCatalogException(field + " is required");
    }
    return value.trim();
  }

  private static String nullableText(JsonNode value) {
    return value.isMissingNode() || value.isNull() ? null : value.asText();
  }

  private static Integer nullableInteger(JsonNode value) {
    return value.isMissingNode() || value.isNull() ? null : value.asInt();
  }

  private static String identityKey(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private static ProfileAuditAction parseAction(String value, Path path) {
    try {
      return ProfileAuditAction.valueOf(requireText(value, "publication action"));
    } catch (IllegalArgumentException exception) {
      throw new ProfileCatalogException("Invalid publication action in " + path, exception);
    }
  }

  private static Instant parseInstant(String value, Path path) {
    try {
      return Instant.parse(requireText(value, "timestamp"));
    } catch (IllegalArgumentException exception) {
      throw new ProfileCatalogException("Invalid timestamp in " + path, exception);
    }
  }
}
