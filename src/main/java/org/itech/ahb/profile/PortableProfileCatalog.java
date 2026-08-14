package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;

/** Durable, append-only catalog for Bridge-owned portable profile revisions. */
public class PortableProfileCatalog {

  private static final Pattern PROFILE_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]+$");
  private static final Set<String> SOURCES = Set.of("SHIPPED", "SITE");
  private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
  private static final Set<String> PROTOCOLS = Set.of("ASTM", "HL7", "FILE");

  private final Path catalogDirectory;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Map<String, NavigableMap<Integer, ProfileCatalogEntry>> revisions = new ConcurrentHashMap<>();

  public PortableProfileCatalog(
    Path catalogDirectory,
    List<Resource> shippedProfiles,
    ObjectMapper objectMapper,
    Clock clock
  ) throws IOException {
    this.catalogDirectory = catalogDirectory;
    this.objectMapper = objectMapper;
    this.clock = clock;
    Files.createDirectories(catalogDirectory);
    loadShipped(shippedProfiles);
    loadPersisted();
    validateUniqueLatestDisplayNames();
  }

  public synchronized List<ProfileCatalogEntry> list(ProfileCatalogFilter filter) {
    ProfileCatalogFilter effectiveFilter = filter == null ? ProfileCatalogFilter.all() : filter;
    return revisions
      .values()
      .stream()
      .map(NavigableMap::lastEntry)
      .map(Map.Entry::getValue)
      .filter(effectiveFilter::matches)
      .sorted(
        Comparator.comparing(entry -> entry.profile().path("displayName").asText(), String.CASE_INSENSITIVE_ORDER)
      )
      .toList();
  }

  public synchronized ProfileCatalogEntry require(String profileId, int revision) {
    NavigableMap<Integer, ProfileCatalogEntry> history = revisions.get(profileId);
    ProfileCatalogEntry entry = history == null ? null : history.get(revision);
    if (entry == null) {
      throw new ProfileCatalogException("Portable profile not found: " + profileId + " revision " + revision);
    }
    return entry;
  }

  public synchronized ProfileCatalogEntry requireLatest(String profileId) {
    NavigableMap<Integer, ProfileCatalogEntry> history = revisions.get(profileId);
    if (history == null || history.isEmpty()) {
      throw new ProfileCatalogException("Portable profile not found: " + profileId);
    }
    return history.lastEntry().getValue();
  }

  public synchronized List<ProfileCatalogEntry> history(String profileId) {
    NavigableMap<Integer, ProfileCatalogEntry> history = revisions.get(profileId);
    if (history == null) {
      throw new ProfileCatalogException("Portable profile not found: " + profileId);
    }
    return List.copyOf(history.values());
  }

  public synchronized ProfileCatalogEntry createSite(JsonNode candidate, String actor) {
    ObjectNode profile = requireObject(candidate);
    String profileId = text(profile, "profileId");
    if (revisions.containsKey(profileId)) {
      throw new ProfileCatalogException("profileId already exists: " + profileId);
    }
    ObjectNode created = profile.deepCopy();
    created.put("source", "SITE");
    created.put("status", "ACTIVE");
    created.put("revision", 1);
    return append(created, ProfileAuditAction.CREATED, actor, true);
  }

  public synchronized ProfileCatalogEntry fork(
    String sourceProfileId,
    int sourceRevision,
    String targetProfileId,
    String displayName,
    String actor
  ) {
    ProfileCatalogEntry parent = require(sourceProfileId, sourceRevision);
    if (!"ACTIVE".equals(parent.profile().path("status").asText())) {
      throw new ProfileCatalogException("Cannot fork an inactive profile");
    }
    if (revisions.containsKey(targetProfileId)) {
      throw new ProfileCatalogException("profileId already exists: " + targetProfileId);
    }

    ObjectNode fork = requireObject(parent.profile()).deepCopy();
    fork.put("profileId", targetProfileId);
    fork.put("displayName", displayName);
    fork.put("source", "SITE");
    fork.put("status", "ACTIVE");
    fork.put("revision", 1);
    ObjectNode lineage = objectMapper.createObjectNode();
    lineage.put("parentProfileId", sourceProfileId);
    lineage.put("parentRevision", sourceRevision);
    fork.set("lineage", lineage);
    return append(fork, ProfileAuditAction.FORKED, actor, true);
  }

  public synchronized ProfileCatalogEntry revise(String profileId, JsonNode candidate, String actor) {
    ProfileCatalogEntry latest = requireLatest(profileId);
    ObjectNode revision = requireObject(candidate).deepCopy();
    revision.put("profileId", profileId);
    revision.put("revision", latest.profile().path("revision").asInt() + 1);
    revision.put("source", latest.profile().path("source").asText());
    revision.put("status", latest.profile().path("status").asText());
    if (latest.profile().has("lineage")) {
      revision.set("lineage", latest.profile().path("lineage").deepCopy());
    } else {
      revision.remove("lineage");
    }
    return append(revision, ProfileAuditAction.UPDATED, actor, false);
  }

  public synchronized ProfileCatalogEntry deactivate(String profileId, String actor) {
    return changeStatus(profileId, "INACTIVE", ProfileAuditAction.DEACTIVATED, actor);
  }

  public synchronized ProfileCatalogEntry reactivate(String profileId, String actor) {
    return changeStatus(profileId, "ACTIVE", ProfileAuditAction.REACTIVATED, actor);
  }

  private ProfileCatalogEntry changeStatus(String profileId, String status, ProfileAuditAction action, String actor) {
    ProfileCatalogEntry latest = requireLatest(profileId);
    String currentStatus = latest.profile().path("status").asText();
    if (status.equals(currentStatus)) {
      throw new ProfileCatalogException("Profile is already " + status.toLowerCase(Locale.ROOT));
    }
    ObjectNode revision = requireObject(latest.profile()).deepCopy();
    revision.put("revision", latest.profile().path("revision").asInt() + 1);
    revision.put("status", status);
    return append(revision, action, actor, false);
  }

  private ProfileCatalogEntry append(ObjectNode profile, ProfileAuditAction action, String actor, boolean newIdentity) {
    requireActor(actor);
    validate(profile);
    requireUniqueDisplayName(
      profile.path("displayName").asText(),
      newIdentity ? null : profile.path("profileId").asText()
    );

    ProfileCatalogEntry entry = new ProfileCatalogEntry(
      profile.deepCopy(),
      new ProfileAuditEvent(action, actor.trim(), clock.instant()),
      fingerprint(profile)
    );
    persist(entry);
    addEntry(entry, false);
    return entry;
  }

  private void loadShipped(List<Resource> shippedProfiles) throws IOException {
    for (Resource resource : shippedProfiles) {
      try (InputStream input = resource.getInputStream()) {
        ObjectNode profile = requireObject(objectMapper.readTree(input));
        validate(profile);
        if (!"SHIPPED".equals(profile.path("source").asText())) {
          throw new ProfileCatalogException("Packaged profile must have source SHIPPED: " + resource.getFilename());
        }
        ProfileCatalogEntry entry = new ProfileCatalogEntry(
          profile.deepCopy(),
          new ProfileAuditEvent(ProfileAuditAction.SHIPPED, "distribution", Instant.EPOCH),
          fingerprint(profile)
        );
        addEntry(entry, false);
      }
    }
  }

  private void loadPersisted() throws IOException {
    try (var files = Files.walk(catalogDirectory)) {
      for (Path file : files
        .filter(Files::isRegularFile)
        .filter(path -> path.getFileName().toString().endsWith(".json"))
        .sorted()
        .toList()) {
        addEntry(readEnvelope(file), true);
      }
    }
  }

  private ProfileCatalogEntry readEnvelope(Path file) {
    try {
      JsonNode envelope = objectMapper.readTree(file.toFile());
      ObjectNode profile = requireObject(envelope.path("profile"));
      validate(profile);
      JsonNode audit = envelope.path("audit");
      ProfileAuditEvent event = new ProfileAuditEvent(
        ProfileAuditAction.valueOf(text(audit, "action")),
        text(audit, "actor"),
        Instant.parse(text(audit, "markedAt"))
      );
      String expectedFingerprint = fingerprint(profile);
      String storedFingerprint = text(envelope, "fingerprint");
      if (!expectedFingerprint.equals(storedFingerprint)) {
        throw new ProfileCatalogException("Profile fingerprint mismatch in " + file);
      }
      return new ProfileCatalogEntry(profile.deepCopy(), event, storedFingerprint);
    } catch (IOException | IllegalArgumentException e) {
      throw new ProfileCatalogException("Cannot read profile revision " + file, e);
    }
  }

  private void persist(ProfileCatalogEntry entry) {
    String profileId = entry.profile().path("profileId").asText();
    int revision = entry.profile().path("revision").asInt();
    Path profileDirectory = catalogDirectory.resolve(profileId);
    Path target = profileDirectory.resolve(revision + ".json");
    Path temporary = profileDirectory.resolve(revision + ".json.tmp");
    if (Files.exists(target)) {
      throw new ProfileCatalogException("Profile revision already exists: " + profileId + " revision " + revision);
    }

    ObjectNode envelope = objectMapper.createObjectNode();
    envelope.set("profile", entry.profile());
    ObjectNode audit = envelope.putObject("audit");
    audit.put("action", entry.audit().action().name());
    audit.put("actor", entry.audit().actor());
    audit.put("markedAt", entry.audit().markedAt().toString());
    envelope.put("fingerprint", entry.fingerprint());

    try {
      Files.createDirectories(profileDirectory);
      Files.writeString(
        temporary,
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope) + "\n",
        StandardCharsets.UTF_8
      );
      moveAtomically(temporary, target);
    } catch (IOException e) {
      throw new ProfileCatalogException("Cannot persist profile revision: " + profileId + " revision " + revision, e);
    }
  }

  private static void moveAtomically(Path temporary, Path target) throws IOException {
    try {
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void addEntry(ProfileCatalogEntry entry, boolean replacePackagedRevision) {
    String profileId = entry.profile().path("profileId").asText();
    int revision = entry.profile().path("revision").asInt();
    NavigableMap<Integer, ProfileCatalogEntry> history = revisions.computeIfAbsent(
      profileId,
      ignored -> new TreeMap<>()
    );
    ProfileCatalogEntry previous = history.putIfAbsent(revision, entry);
    if (previous != null) {
      if (replacePackagedRevision && previous.audit().action() == ProfileAuditAction.SHIPPED) {
        history.put(revision, entry);
        return;
      }
      throw new ProfileCatalogException("Duplicate profile revision: " + profileId + " revision " + revision);
    }
  }

  private void validateUniqueLatestDisplayNames() {
    Set<String> names = new LinkedHashSet<>();
    for (ProfileCatalogEntry entry : list(ProfileCatalogFilter.all())) {
      String normalized = normalizeName(entry.profile().path("displayName").asText());
      if (!names.add(normalized)) {
        throw new ProfileCatalogException(
          "Duplicate displayName in profile catalog: " + entry.profile().path("displayName").asText()
        );
      }
    }
  }

  private void requireUniqueDisplayName(String displayName, String sameProfileId) {
    String normalized = normalizeName(displayName);
    for (ProfileCatalogEntry entry : list(ProfileCatalogFilter.all())) {
      String existingProfileId = entry.profile().path("profileId").asText();
      if (
        !existingProfileId.equals(sameProfileId) &&
        normalizeName(entry.profile().path("displayName").asText()).equals(normalized)
      ) {
        throw new ProfileCatalogException("displayName already exists: " + displayName.trim());
      }
    }
  }

  private void validate(ObjectNode profile) {
    String schemaVersion = text(profile, "schemaVersion");
    String profileId = text(profile, "profileId");
    String displayName = text(profile, "displayName");
    String source = text(profile, "source");
    String status = text(profile, "status");
    String protocol = text(profile, "protocol");
    int revision = profile.path("revision").asInt(0);

    if (!"1.0".equals(schemaVersion)) {
      throw new ProfileCatalogException("schemaVersion must be 1.0");
    }
    if (!PROFILE_ID.matcher(profileId).matches()) {
      throw new ProfileCatalogException("Invalid profileId: " + profileId);
    }
    if (displayName.isBlank()) {
      throw new ProfileCatalogException("displayName is required");
    }
    if (revision < 1) {
      throw new ProfileCatalogException("revision must be at least 1");
    }
    if (!SOURCES.contains(source)) {
      throw new ProfileCatalogException("Invalid profile source: " + source);
    }
    if (!STATUSES.contains(status)) {
      throw new ProfileCatalogException("Invalid profile status: " + status);
    }
    if (!PROTOCOLS.contains(protocol)) {
      throw new ProfileCatalogException("Invalid profile protocol: " + protocol);
    }
    if (
      !profile.path("capabilities").isObject() ||
      !profile.path("tests").isArray() ||
      !profile.path("qcIdentification").isArray()
    ) {
      throw new ProfileCatalogException("capabilities, tests, and qcIdentification are required");
    }
    requireUniqueKeys(profile.path("tests"), "sourceRowKey");
    requireUniqueKeys(profile.path("qcIdentification"), "ruleKey");
  }

  private void requireUniqueKeys(JsonNode rows, String key) {
    Set<String> values = new LinkedHashSet<>();
    for (JsonNode row : rows) {
      String value = text(row, key);
      if (!values.add(value)) {
        throw new ProfileCatalogException("Duplicate " + key + ": " + value);
      }
    }
  }

  private String fingerprint(JsonNode profile) {
    try {
      byte[] canonical = objectMapper.writeValueAsBytes(canonicalize(profile));
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException | IOException e) {
      throw new ProfileCatalogException("Cannot fingerprint portable profile", e);
    }
  }

  private JsonNode canonicalize(JsonNode node) {
    if (node.isObject()) {
      ObjectNode canonical = objectMapper.createObjectNode();
      List<String> names = new ArrayList<>();
      Iterator<String> iterator = node.fieldNames();
      iterator.forEachRemaining(names::add);
      names.stream().sorted().forEach(name -> canonical.set(name, canonicalize(node.path(name))));
      return canonical;
    }
    if (node.isArray()) {
      ArrayNode canonical = objectMapper.createArrayNode();
      node.forEach(value -> canonical.add(canonicalize(value)));
      return canonical;
    }
    return node.deepCopy();
  }

  private static ObjectNode requireObject(JsonNode node) {
    if (!(node instanceof ObjectNode object)) {
      throw new ProfileCatalogException("Portable profile must be a JSON object");
    }
    return object;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new ProfileCatalogException(field + " is required");
    }
    return value.asText();
  }

  private static void requireActor(String actor) {
    if (actor == null || actor.isBlank()) {
      throw new ProfileCatalogException("actor is required");
    }
  }

  private static String normalizeName(String displayName) {
    return displayName.trim().toLowerCase(Locale.ROOT);
  }
}
