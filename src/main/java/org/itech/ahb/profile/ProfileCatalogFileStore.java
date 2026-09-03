package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

final class ProfileCatalogFileStore {

  private static final String DRAFT_DIRECTORY = ".drafts";

  private final Path catalogDirectory;
  private final Path draftDirectory;
  private final ObjectMapper objectMapper;

  ProfileCatalogFileStore(Path catalogDirectory, ObjectMapper objectMapper) {
    this.catalogDirectory = catalogDirectory.toAbsolutePath().normalize();
    draftDirectory = this.catalogDirectory.resolve(DRAFT_DIRECTORY);
    this.objectMapper = objectMapper;
  }

  List<StoredDocument> revisionDocuments() {
    if (!Files.exists(catalogDirectory)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(catalogDirectory)) {
      return files
        .filter(Files::isRegularFile)
        .filter(path -> path.getFileName().toString().matches("[1-9][0-9]*\\.json"))
        .sorted()
        .map(this::readDocument)
        .toList();
    } catch (IOException exception) {
      throw new ProfileCatalogException("Cannot scan profile catalog " + catalogDirectory, exception);
    }
  }

  List<StoredDocument> draftDocuments() {
    if (!Files.exists(draftDirectory)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(draftDirectory)) {
      return files
        .filter(Files::isRegularFile)
        .filter(path -> path.toString().endsWith(".json"))
        .sorted()
        .map(this::readDocument)
        .toList();
    } catch (IOException exception) {
      throw new ProfileCatalogException("Cannot scan profile drafts " + draftDirectory, exception);
    }
  }

  Path persistRevision(ProfileRevision revision) {
    ObjectNode profile = revision.profile();
    String profileId = profile.path("profileMeta").path("id").asText();
    Path profileDirectory = catalogDirectory.resolve(profileId).normalize();
    if (!profileDirectory.getParent().equals(catalogDirectory)) {
      throw new ProfileCatalogException("profile ID does not resolve inside the catalog directory");
    }
    Path target = profileDirectory.resolve(profile.path("catalog").path("revision").asInt() + ".json");
    ObjectNode envelope = objectMapper.createObjectNode();
    envelope.set("profile", profile);
    ObjectNode publication = envelope.putObject("publication");
    publication.put("action", revision.publication().action().name());
    publication.put("actor", revision.publication().actor());
    publication.put("markedAt", revision.publication().markedAt().toString());
    writeNew(profileDirectory, target, envelope, "profile revision");
    return target;
  }

  void persistDraft(ProfileDraft draft) {
    Path target = draftPath(draft.draftId());
    ObjectNode document = objectMapper.createObjectNode();
    document.put("draftId", draft.draftId());
    document.put("kind", draft.kind().name());
    putNullable(document, "baseProfileId", draft.baseProfileId());
    putNullable(document, "baseRevision", draft.baseRevision());
    document.set("profile", draft.profile());
    document.put("createdBy", draft.createdBy());
    document.put("createdAt", draft.createdAt().toString());
    document.put("updatedBy", draft.updatedBy());
    document.put("updatedAt", draft.updatedAt().toString());
    writeReplacing(draftDirectory, target, document, "profile draft");
  }

  void deleteDraft(String draftId) {
    try {
      Files.delete(draftPath(draftId));
    } catch (IOException exception) {
      throw new ProfileCatalogException("Cannot remove published profile draft " + draftId, exception);
    }
  }

  void rollbackRevision(Path revisionPath, RuntimeException original) {
    try {
      Files.deleteIfExists(revisionPath);
    } catch (IOException cleanupFailure) {
      original.addSuppressed(cleanupFailure);
    }
  }

  private StoredDocument readDocument(Path path) {
    try {
      JsonNode document = objectMapper.readTree(path.toFile());
      if (!(document instanceof ObjectNode object)) {
        throw new ProfileCatalogException("Persisted catalog document must be a JSON object: " + path);
      }
      return new StoredDocument(path, object);
    } catch (IOException exception) {
      throw new ProfileCatalogException("Cannot read persisted catalog document " + path, exception);
    }
  }

  private void writeNew(Path directory, Path target, ObjectNode document, String description) {
    Path temporary = temporaryPath(directory, target);
    try {
      Files.createDirectories(directory);
      if (Files.exists(target)) {
        throw new ProfileCatalogException(description + " already exists: " + target);
      }
      Files.write(temporary, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document));
      moveWithoutReplacement(temporary, target);
    } catch (IOException exception) {
      deleteTemporary(temporary, exception);
      throw new ProfileCatalogException("Cannot persist " + description + " " + target, exception);
    }
  }

  private void writeReplacing(Path directory, Path target, ObjectNode document, String description) {
    Path temporary = temporaryPath(directory, target);
    try {
      Files.createDirectories(directory);
      Files.write(temporary, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document));
      moveReplacing(temporary, target);
    } catch (IOException exception) {
      deleteTemporary(temporary, exception);
      throw new ProfileCatalogException("Cannot persist " + description + " " + target, exception);
    }
  }

  private Path draftPath(String draftId) {
    UUID.fromString(draftId);
    Path target = draftDirectory.resolve(draftId + ".json").normalize();
    if (!target.getParent().equals(draftDirectory)) {
      throw new ProfileCatalogException("draft ID does not resolve inside the draft directory");
    }
    return target;
  }

  private static Path temporaryPath(Path directory, Path target) {
    return directory.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
  }

  private static void putNullable(ObjectNode document, String field, String value) {
    if (value == null) {
      document.putNull(field);
    } else {
      document.put(field, value);
    }
  }

  private static void putNullable(ObjectNode document, String field, Integer value) {
    if (value == null) {
      document.putNull(field);
    } else {
      document.put(field, value);
    }
  }

  private static void moveWithoutReplacement(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, target);
    }
  }

  private static void moveReplacing(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void deleteTemporary(Path temporary, IOException original) {
    try {
      Files.deleteIfExists(temporary);
    } catch (IOException cleanupFailure) {
      original.addSuppressed(cleanupFailure);
    }
  }

  record StoredDocument(Path path, ObjectNode document) {}
}
