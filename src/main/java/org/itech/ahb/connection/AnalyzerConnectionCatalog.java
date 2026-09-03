package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.itech.ahb.profile.AnalyzerProfileCatalog;
import org.itech.ahb.profile.ProfileFingerprintService;
import org.itech.ahb.connection.AnalyzerConnectionException.Kind;

/** Durable, profile-pinned analyzer connections owned by Bridge. */
public final class AnalyzerConnectionCatalog {

  private static final String SCHEMA_VERSION = "1.0";

  private final Path directory;
  private final AnalyzerProfileCatalog profiles;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Supplier<UUID> ids;
  private final AnalyzerConnectionRuntime runtime;
  private final ProfileFingerprintService fingerprints = new ProfileFingerprintService();
  private final Map<String, ObjectNode> connections = new HashMap<>();
  private final Map<String, String> connectionIdByClientAnalyzerId = new HashMap<>();

  public AnalyzerConnectionCatalog(
    Path directory,
    AnalyzerProfileCatalog profiles,
    ObjectMapper objectMapper,
    Clock clock
  ) {
    this(directory, profiles, objectMapper, clock, UUID::randomUUID, AnalyzerConnectionRuntime.noOp());
  }

  AnalyzerConnectionCatalog(
    Path directory,
    AnalyzerProfileCatalog profiles,
    ObjectMapper objectMapper,
    Clock clock,
    Supplier<UUID> ids
  ) {
    this(directory, profiles, objectMapper, clock, ids, AnalyzerConnectionRuntime.noOp());
  }

  AnalyzerConnectionCatalog(
    Path directory,
    AnalyzerProfileCatalog profiles,
    ObjectMapper objectMapper,
    Clock clock,
    Supplier<UUID> ids,
    AnalyzerConnectionRuntime runtime
  ) {
    this.directory = directory.toAbsolutePath().normalize();
    this.profiles = profiles;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.ids = ids;
    this.runtime = runtime;
    load();
    restoreActiveConnections();
  }

  public synchronized ObjectNode create(ObjectNode request) {
    requireVersion(request);
    String requestId = requireText(request, "requestId");
    String clientAnalyzerId = requireText(request, "clientAnalyzerId");
    String displayName = requireText(request, "displayName");
    ObjectNode profileRef = requireObject(request, "profileRef");
    ObjectNode suppliedValues = requireObject(request, "values");
    ObjectNode profile = requirePinnedProfile(profileRef);
    validateValues(profile, suppliedValues);

    String existingId = connectionIdByClientAnalyzerId.get(clientAnalyzerId);
    if (existingId != null) {
      ObjectNode existing = connections.get(existingId);
      if (requestId.equals(existing.path("createRequestId").asText()) ||
          sameConfiguration(existing, profileRef, displayName, effectiveValues(profile, suppliedValues))) {
        return view(existing, profile);
      }
      throw new AnalyzerConnectionException(
        Kind.CONFLICT,
        "A different Bridge connection already exists for OpenELIS analyzer " + clientAnalyzerId
      );
    }

    String connectionId = ids.get().toString();
    ObjectNode values = effectiveValues(profile, suppliedValues);
    ObjectNode record = objectMapper.createObjectNode();
    record.put("connectionId", connectionId);
    record.put("clientAnalyzerId", clientAnalyzerId);
    record.put("createRequestId", requestId);
    record.put("displayName", displayName);
    record.set("profileRef", profileRef.deepCopy());
    record.put("configRevision", 1);
    record.set("values", values);
    record.put("configFingerprint", configurationFingerprint(profileRef, displayName, values));
    record.putNull("latestProbe");
    record.put("desiredRuntimeState", "INACTIVE");
    record.put("actualRuntimeState", "INACTIVE");
    record.putNull("activeRuntimeRef");
    record.put("runtimeRevision", 1);
    record.put("runtimeFingerprint", runtimeFingerprint(record, "INACTIVE", 1));
    record.putObject("runtimeCommandAcks");
    record.put("updatedAt", clock.instant().toString());
    persist(record);
    add(record);
    return view(record, profile);
  }

  public synchronized ObjectNode update(ObjectNode request) {
    requireVersion(request);
    requireText(request, "requestId");
    String connectionId = requireText(request, "connectionId");
    ObjectNode existing = requireRecord(connectionId);
    int expectedRevision = requirePositiveInteger(request, "expectedConfigRevision");
    if (existing.path("configRevision").asInt() != expectedRevision) {
      throw new AnalyzerConnectionException(
        Kind.CONFLICT,
        "Expected configuration revision " + expectedRevision +
        " does not match current revision " + existing.path("configRevision").asInt()
      );
    }

    String displayName = requireText(request, "displayName");
    ObjectNode profileRef = requireObject(request, "profileRef");
    ObjectNode suppliedValues = requireObject(request, "values");
    ObjectNode profile = requirePinnedProfile(profileRef);
    validateValues(profile, suppliedValues);
    ObjectNode values = effectiveValues(profile, suppliedValues, (ObjectNode) existing.path("values"));

    ObjectNode changed = existing.deepCopy();
    changed.put("displayName", displayName);
    changed.set("profileRef", profileRef.deepCopy());
    changed.put("configRevision", expectedRevision + 1);
    changed.set("values", values);
    changed.put("configFingerprint", configurationFingerprint(profileRef, displayName, values));
    changed.put("updatedAt", clock.instant().toString());
    persist(changed);
    add(changed);
    return view(changed, profile);
  }

  public synchronized ObjectNode require(String connectionId) {
    ObjectNode record = requireRecord(connectionId);
    ObjectNode profile = requirePinnedProfile((ObjectNode) record.path("profileRef"));
    return view(record, profile);
  }

  public synchronized ObjectNode probe(ObjectNode request, AnalyzerConnectionProbe probe) {
    requireVersion(request);
    requireText(request, "requestId");
    String connectionId = requireText(request, "connectionId");
    ObjectNode record = requireRecord(connectionId);
    int expectedRevision = requirePositiveInteger(request, "expectedConfigRevision");
    if (record.path("configRevision").asInt() != expectedRevision) {
      throw new AnalyzerConnectionException(
        Kind.CONFLICT,
        "Expected configuration revision " + expectedRevision +
        " does not match current revision " + record.path("configRevision").asInt()
      );
    }

    ObjectNode profile = requirePinnedProfile((ObjectNode) record.path("profileRef"));
    ObjectNode result = probe.execute(request, record.deepCopy(), profile);
    ObjectNode changed = record.deepCopy();
    ObjectNode summary = objectMapper.createObjectNode();
    copy(summary, result, "requestId");
    copy(summary, result, "configRevision");
    copy(summary, result, "status");
    copy(summary, result, "completedAt");
    changed.set("latestProbe", summary);
    persist(changed);
    add(changed);
    return result;
  }

  public synchronized ObjectNode applyRuntimeCommand(ObjectNode command) {
    requireVersion(command);
    String commandId = requireText(command, "commandId");
    String connectionId = requireText(command, "connectionId");
    String action = requireText(command, "action");
    if (!Set.of("ACTIVATE", "DEACTIVATE").contains(action)) {
      throw new AnalyzerConnectionException("action must be ACTIVATE or DEACTIVATE");
    }

    ObjectNode record = requireRecord(connectionId);
    int expectedRevision = requirePositiveInteger(command, "expectedConfigRevision");
    if (record.path("configRevision").asInt() != expectedRevision) {
      throw new AnalyzerConnectionException(
        Kind.CONFLICT,
        "Expected configuration revision " + expectedRevision +
        " does not match current revision " + record.path("configRevision").asInt()
      );
    }
    ObjectNode acknowledgements = record.withObject("runtimeCommandAcks");
    if (acknowledgements.path(commandId) instanceof ObjectNode prior) {
      return prior.deepCopy();
    }

    ObjectNode profile = requirePinnedProfile((ObjectNode) record.path("profileRef"));
    ObjectNode acknowledgement;
    if ("ACTIVATE".equals(action)) {
      acknowledgement = activate(record, profile, commandId);
    } else {
      acknowledgement = deactivate(record, profile, commandId);
    }
    record.withObject("runtimeCommandAcks").set(commandId, acknowledgement.deepCopy());
    persist(record);
    add(record);
    return acknowledgement;
  }

  private ObjectNode activate(ObjectNode record, ObjectNode profile, String commandId) {
    ArrayList<String> missing = missingRequiredFields(record, profile);
    if (!missing.isEmpty()) {
      ObjectNode acknowledgement = runtimeAcknowledgement(record, commandId, "ACTIVATE", "REJECTED");
      ObjectNode blocker = acknowledgement.withArray("blockers").addObject();
      blocker.put("key", "missing-required-values");
      blocker.put("messageKey", "analyzer.connection.readiness.missingRequiredValues");
      return acknowledgement;
    }
    if ("ACTIVE".equals(record.path("actualRuntimeState").asText()) && activeRuntimeMatchesConfiguration(record)) {
      return runtimeAcknowledgement(record, commandId, "ACTIVATE", "ALREADY_APPLIED");
    }

    runtime.activate(record.deepCopy(), profile.deepCopy());
    int runtimeRevision = record.path("runtimeRevision").asInt(1) + 1;
    String runtimeFingerprint = runtimeFingerprint(record, "ACTIVE", runtimeRevision);
    record.put("desiredRuntimeState", "ACTIVE");
    record.put("actualRuntimeState", "ACTIVE");
    record.put("runtimeRevision", runtimeRevision);
    record.put("runtimeFingerprint", runtimeFingerprint);
    ObjectNode active = record.putObject("activeRuntimeRef");
    active.put("connectionId", record.path("connectionId").asText());
    active.set("profileRef", record.path("profileRef").deepCopy());
    active.put("configRevision", record.path("configRevision").asInt());
    active.put("configFingerprint", record.path("configFingerprint").asText());
    active.put("runtimeRevision", runtimeRevision);
    active.put("runtimeFingerprint", runtimeFingerprint);
    record.put("updatedAt", clock.instant().toString());
    return runtimeAcknowledgement(record, commandId, "ACTIVATE", "APPLIED");
  }

  private ObjectNode deactivate(ObjectNode record, ObjectNode profile, String commandId) {
    if ("INACTIVE".equals(record.path("actualRuntimeState").asText())) {
      return runtimeAcknowledgement(record, commandId, "DEACTIVATE", "ALREADY_APPLIED");
    }

    runtime.deactivate(record.deepCopy(), profile.deepCopy());
    int runtimeRevision = record.path("runtimeRevision").asInt(1) + 1;
    String runtimeFingerprint = runtimeFingerprint(record, "INACTIVE", runtimeRevision);
    record.put("desiredRuntimeState", "INACTIVE");
    record.put("actualRuntimeState", "INACTIVE");
    record.put("runtimeRevision", runtimeRevision);
    record.put("runtimeFingerprint", runtimeFingerprint);
    record.putNull("activeRuntimeRef");
    record.put("updatedAt", clock.instant().toString());
    return runtimeAcknowledgement(record, commandId, "DEACTIVATE", "APPLIED");
  }

  private ObjectNode runtimeAcknowledgement(
    ObjectNode record,
    String commandId,
    String action,
    String outcome
  ) {
    ObjectNode acknowledgement = objectMapper.createObjectNode();
    acknowledgement.put("schemaVersion", SCHEMA_VERSION);
    acknowledgement.put("commandId", commandId);
    acknowledgement.put("action", action);
    acknowledgement.put("outcome", outcome);
    copy(acknowledgement, record, "connectionId");
    acknowledgement.set("profileRef", record.path("profileRef").deepCopy());
    copy(acknowledgement, record, "configRevision");
    copy(acknowledgement, record, "configFingerprint");
    copy(acknowledgement, record, "runtimeRevision");
    copy(acknowledgement, record, "runtimeFingerprint");
    copy(acknowledgement, record, "desiredRuntimeState");
    copy(acknowledgement, record, "actualRuntimeState");
    acknowledgement.putArray("blockers");
    acknowledgement.put("acknowledgedAt", clock.instant().toString());
    return acknowledgement;
  }

  private boolean activeRuntimeMatchesConfiguration(ObjectNode record) {
    JsonNode active = record.path("activeRuntimeRef");
    return active.isObject() &&
    active.path("profileRef").equals(record.path("profileRef")) &&
    active.path("configRevision").asInt() == record.path("configRevision").asInt() &&
    active.path("configFingerprint").asText().equals(record.path("configFingerprint").asText());
  }

  private String runtimeFingerprint(ObjectNode record, String state, int runtimeRevision) {
    ObjectNode materialization = objectMapper.createObjectNode();
    materialization.put("connectionId", record.path("connectionId").asText());
    materialization.set("profileRef", record.path("profileRef").deepCopy());
    materialization.put("configRevision", record.path("configRevision").asInt());
    materialization.put("configFingerprint", record.path("configFingerprint").asText());
    materialization.put("state", state);
    materialization.put("runtimeRevision", runtimeRevision);
    return fingerprints.canonicalFingerprint(materialization);
  }

  private void restoreActiveConnections() {
    connections.values().stream()
      .filter(record -> "ACTIVE".equals(record.path("actualRuntimeState").asText()))
      .sorted(Comparator.comparing(record -> record.path("connectionId").asText()))
      .forEach(record -> {
        ObjectNode profile = requirePinnedProfile((ObjectNode) record.path("profileRef"));
        runtime.restore(record.deepCopy(), profile.deepCopy());
      });
  }

  private ObjectNode requireRecord(String connectionId) {
    ObjectNode record = connections.get(connectionId);
    if (record == null) {
      throw new AnalyzerConnectionException(Kind.NOT_FOUND, "Unknown Bridge connection: " + connectionId);
    }
    return record;
  }

  private ObjectNode requirePinnedProfile(ObjectNode profileRef) {
    String profileId = requireText(profileRef, "profileId");
    int revision = requirePositiveInteger(profileRef, "revision");
    String fingerprint = requireText(profileRef, "fingerprint");
    ObjectNode profile;
    try {
      profile = profiles.require(profileId, revision).profile();
    } catch (RuntimeException exception) {
      throw new AnalyzerConnectionException("Unknown pinned profile revision " + profileId + "@" + revision, exception);
    }
    String actualFingerprint = profile.path("catalog").path("revisionFingerprint").asText();
    if (!actualFingerprint.equals(fingerprint)) {
      throw new AnalyzerConnectionException("Profile fingerprint does not identify " + profileId + "@" + revision);
    }
    return profile;
  }

  private ObjectNode effectiveValues(ObjectNode profile, ObjectNode suppliedValues) {
    return effectiveValues(profile, suppliedValues, null);
  }

  private ObjectNode effectiveValues(
    ObjectNode profile,
    ObjectNode suppliedValues,
    ObjectNode existingValues
  ) {
    ObjectNode values = profileDefaults(profile);
    if (existingValues != null) {
      for (JsonNode descriptor : profile.path("connectionFields")) {
        String key = descriptor.path("key").asText();
        if (!suppliedValues.has(key) && existingValues.has(key)) {
          values.set(key, existingValues.path(key).deepCopy());
        }
      }
    }
    suppliedValues.fields().forEachRemaining(entry -> values.set(entry.getKey(), entry.getValue().deepCopy()));
    return values;
  }

  private ObjectNode profileDefaults(ObjectNode profile) {
    ObjectNode values = objectMapper.createObjectNode();
    JsonNode defaults = profile.path("configDefaults");
    if (defaults.isObject()) {
      defaults.fields().forEachRemaining(entry -> values.set(entry.getKey(), entry.getValue().deepCopy()));
    }
    return values;
  }

  private boolean sameConfiguration(
    ObjectNode record,
    ObjectNode profileRef,
    String displayName,
    ObjectNode values
  ) {
    return record.path("profileRef").equals(profileRef) &&
    displayName.equals(record.path("displayName").asText()) &&
    record.path("values").equals(values);
  }

  private String configurationFingerprint(ObjectNode profileRef, String displayName, ObjectNode values) {
    ObjectNode configuration = objectMapper.createObjectNode();
    configuration.set("profileRef", profileRef.deepCopy());
    configuration.put("displayName", displayName);
    configuration.set("values", values.deepCopy());
    return fingerprints.canonicalFingerprint(configuration);
  }

  private ObjectNode view(ObjectNode record, ObjectNode profile) {
    ObjectNode response = objectMapper.createObjectNode();
    response.put("schemaVersion", SCHEMA_VERSION);
    copy(response, record, "connectionId");
    copy(response, record, "clientAnalyzerId");
    copy(response, record, "displayName");
    response.set("profileRef", record.path("profileRef").deepCopy());
    copy(response, record, "configRevision");
    copy(response, record, "configFingerprint");
    response.set("fields", fields(record, profile));
    ArrayList<String> missingFields = missingRequiredFields(record, profile);
    ObjectNode readiness = response.putObject("readiness");
    readiness.put("ready", missingFields.isEmpty());
    ArrayNode blockers = readiness.putArray("blockers");
    if (!missingFields.isEmpty()) {
      ObjectNode blocker = blockers.addObject();
      blocker.put("key", "missing-required-values");
      blocker.put("messageKey", "analyzer.connection.readiness.missingRequiredValues");
      ArrayNode fieldKeys = blocker.putArray("fieldKeys");
      missingFields.forEach(fieldKeys::add);
    }
    response.set("latestProbe", record.path("latestProbe").deepCopy());
    copy(response, record, "desiredRuntimeState");
    copy(response, record, "actualRuntimeState");
    response.set("activeRuntimeRef", record.path("activeRuntimeRef").deepCopy());
    copy(response, record, "updatedAt");
    return response;
  }

  private ArrayNode fields(ObjectNode record, ObjectNode profile) {
    ObjectNode values = (ObjectNode) record.path("values");
    ObjectNode defaults = profileDefaults(profile);
    Map<String, JsonNode> descriptors = fieldDescriptors(profile);
    ArrayNode fields = objectMapper.createArrayNode();
    for (JsonNode descriptor : profile.path("connectionFields")) {
      String key = descriptor.path("key").asText();
      JsonNode currentValue = values.path(key);
      ObjectNode field = fields.addObject();
      field.put("key", key);
      field.put("labelKey", descriptor.path("labelKey").asText());
      if (descriptor.has("helpTextKey")) {
        field.set("helpTextKey", descriptor.path("helpTextKey").deepCopy());
      }
      String inputKind = descriptor.path("inputKind").asText();
      field.put("inputKind", inputKind);
      boolean required = descriptor.path("required").asBoolean();
      field.put("required", required);
      if ("SECRET".equals(inputKind)) {
        field.putNull("defaultValue");
        field.put("isSet", hasValue(currentValue));
        field.put("maskedValue", "********");
      } else {
        field.set("defaultValue", defaults.has(key) ? defaults.path(key).deepCopy() : objectMapper.nullNode());
      }
      if (!"SECRET".equals(inputKind) && !currentValue.isMissingNode()) {
        field.set("currentValue", currentValue.deepCopy());
      }
      field.set("choices", descriptor.path("choices").deepCopy());
      if (descriptor.has("visibleWhen")) {
        field.set("visibleWhen", descriptor.path("visibleWhen").deepCopy());
      }
      ArrayNode validationErrors = field.putArray("validationErrors");
      if (required && isVisible(descriptor, values, descriptors) && !hasValue(currentValue)) {
        validationErrors.add("analyzer.connection.validation.required");
      }
    }
    return fields;
  }

  private ArrayList<String> missingRequiredFields(ObjectNode record, ObjectNode profile) {
    ObjectNode values = (ObjectNode) record.path("values");
    Map<String, JsonNode> descriptors = fieldDescriptors(profile);
    ArrayList<String> missing = new ArrayList<>();
    for (JsonNode descriptor : profile.path("connectionFields")) {
      String key = descriptor.path("key").asText();
      if (
        descriptor.path("required").asBoolean() &&
        isVisible(descriptor, values, descriptors) &&
        !hasValue(values.path(key))
      ) {
        missing.add(key);
      }
    }
    missing.sort(Comparator.naturalOrder());
    return missing;
  }

  private static boolean hasValue(JsonNode value) {
    return !value.isMissingNode() && !value.isNull() && (!value.isTextual() || !value.asText().isBlank());
  }

  private static boolean isVisible(
    JsonNode descriptor,
    ObjectNode values,
    Map<String, JsonNode> descriptors
  ) {
    return isVisible(descriptor, values, descriptors, new HashSet<>());
  }

  private static boolean isVisible(
    JsonNode descriptor,
    ObjectNode values,
    Map<String, JsonNode> descriptors,
    Set<String> visiting
  ) {
    String key = descriptor.path("key").asText();
    if (!visiting.add(key)) {
      return false;
    }
    try {
      JsonNode condition = descriptor.path("visibleWhen");
      if (!condition.isObject()) {
        return true;
      }
      String controllingKey = condition.path("fieldKey").asText();
      JsonNode controllingField = descriptors.get(controllingKey);
      if (
        controllingField != null &&
        !isVisible(controllingField, values, descriptors, visiting)
      ) {
        return false;
      }
      JsonNode actual = values.path(controllingKey);
      JsonNode expected = condition.path("value");
      return switch (condition.path("operator").asText()) {
        case "EQUALS" -> actual.equals(expected);
        case "NOT_EQUALS" -> !actual.equals(expected);
        case "IN" -> expected.isArray() && contains(expected, actual);
        case "NOT_IN" -> expected.isArray() && !contains(expected, actual);
        default -> false;
      };
    } finally {
      visiting.remove(key);
    }
  }

  private static boolean contains(JsonNode values, JsonNode sought) {
    for (JsonNode value : values) {
      if (value.equals(sought)) {
        return true;
      }
    }
    return false;
  }

  private void validateValues(ObjectNode profile, ObjectNode values) {
    Map<String, JsonNode> descriptors = fieldDescriptors(profile);
    Iterator<String> keys = values.fieldNames();
    while (keys.hasNext()) {
      String key = keys.next();
      if (key.isBlank()) {
        throw new AnalyzerConnectionException("Connection value keys must not be blank");
      }
      JsonNode descriptor = descriptors.get(key);
      if (descriptor == null) {
        throw new AnalyzerConnectionException("Connection value is not declared by the pinned profile: " + key);
      }
      if (values.path(key).isNull()) {
        throw new AnalyzerConnectionException("Connection value must not be null: " + key);
      }
      validateValueType(key, descriptor, values.path(key));
    }
  }

  private static Map<String, JsonNode> fieldDescriptors(ObjectNode profile) {
    Map<String, JsonNode> descriptors = new HashMap<>();
    profile.path("connectionFields").forEach(field -> descriptors.put(field.path("key").asText(), field));
    return descriptors;
  }

  private static void validateValueType(String key, JsonNode descriptor, JsonNode value) {
    boolean correctType = switch (descriptor.path("inputKind").asText()) {
      case "NUMBER" -> value.isNumber();
      case "BOOLEAN" -> value.isBoolean();
      case "TEXT", "SECRET", "FILE_PATH", "SELECT" -> value.isTextual();
      default -> false;
    };
    if (!correctType) {
      throw new AnalyzerConnectionException("Connection value has the wrong type for " + key);
    }
    if (
      "SELECT".equals(descriptor.path("inputKind").asText()) &&
      descriptor.path("choices").findValues("value").stream().noneMatch(value::equals)
    ) {
      throw new AnalyzerConnectionException("Connection value is not an allowed choice for " + key);
    }
  }

  private void load() {
    if (!Files.exists(directory)) {
      return;
    }
    try (Stream<Path> files = Files.list(directory)) {
      files
        .filter(Files::isRegularFile)
        .filter(path -> path.getFileName().toString().endsWith(".json"))
        .sorted()
        .map(this::read)
        .forEach(this::add);
    } catch (IOException exception) {
      throw new AnalyzerConnectionException("Cannot scan Bridge connections " + directory, exception);
    }
  }

  private ObjectNode read(Path path) {
    try {
      JsonNode value = objectMapper.readTree(path.toFile());
      if (!(value instanceof ObjectNode object)) {
        throw new AnalyzerConnectionException("Persisted Bridge connection must be an object: " + path);
      }
      requireText(object, "connectionId");
      requireText(object, "clientAnalyzerId");
      requirePinnedProfile(requireObject(object, "profileRef"));
      requirePositiveInteger(object, "configRevision");
      requireObject(object, "values");
      return object;
    } catch (IOException exception) {
      throw new AnalyzerConnectionException("Cannot read persisted Bridge connection " + path, exception);
    }
  }

  private void add(ObjectNode record) {
    String connectionId = record.path("connectionId").asText();
    String clientAnalyzerId = record.path("clientAnalyzerId").asText();
    String prior = connectionIdByClientAnalyzerId.put(clientAnalyzerId, connectionId);
    if (prior != null && !prior.equals(connectionId)) {
      throw new AnalyzerConnectionException("Duplicate OpenELIS analyzer connection: " + clientAnalyzerId);
    }
    connections.put(connectionId, record.deepCopy());
  }

  private void persist(ObjectNode record) {
    String connectionId = record.path("connectionId").asText();
    UUID.fromString(connectionId);
    Path target = directory.resolve(connectionId + ".json").normalize();
    if (!target.getParent().equals(directory)) {
      throw new AnalyzerConnectionException("Connection ID does not resolve inside the connection directory");
    }
    Path temporary = directory.resolve("." + connectionId + "." + UUID.randomUUID() + ".tmp");
    try {
      Files.createDirectories(directory);
      Files.write(temporary, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(record));
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException exception) {
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      throw new AnalyzerConnectionException("Cannot persist Bridge connection " + connectionId, exception);
    }
  }

  private static void requireVersion(ObjectNode request) {
    if (!SCHEMA_VERSION.equals(request.path("schemaVersion").asText())) {
      throw new AnalyzerConnectionException("schemaVersion must be " + SCHEMA_VERSION);
    }
  }

  private static String requireText(JsonNode object, String field) {
    JsonNode value = object.path(field);
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new AnalyzerConnectionException(field + " is required");
    }
    return value.asText();
  }

  private static int requirePositiveInteger(JsonNode object, String field) {
    JsonNode value = object.path(field);
    if (!value.isIntegralNumber() || value.asInt() < 1) {
      throw new AnalyzerConnectionException(field + " must be a positive integer");
    }
    return value.asInt();
  }

  private static ObjectNode requireObject(JsonNode object, String field) {
    JsonNode value = object.path(field);
    if (!(value instanceof ObjectNode child)) {
      throw new AnalyzerConnectionException(field + " must be an object");
    }
    return child;
  }

  private static void copy(ObjectNode target, ObjectNode source, String field) {
    target.set(field, source.path(field).deepCopy());
  }
}
