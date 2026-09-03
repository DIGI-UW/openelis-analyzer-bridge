package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profiles")
public class AnalyzerProfileController {

  private final AnalyzerProfileCatalog catalog;

  public AnalyzerProfileController(AnalyzerProfileCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  public ProfileCatalogResponse list() {
    return new ProfileCatalogResponse("1.0", catalog.catalogFingerprint(), catalog.latest());
  }

  @GetMapping("/drafts")
  public List<ProfileDraft> drafts() {
    return catalog.drafts();
  }

  @GetMapping("/drafts/{draftId}")
  public ProfileDraft draft(@PathVariable String draftId) {
    return catalog.requireDraft(draftId);
  }

  @PostMapping("/drafts")
  public ResponseEntity<ProfileDraft> createDraft(@RequestBody CreateDraftRequest request) {
    requireRequest(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createDraft(request.displayName(), request.actor()));
  }

  @PutMapping("/drafts/{draftId}")
  public ProfileDraft updateDraft(@PathVariable String draftId, @RequestBody ProfileMutationRequest request) {
    if (request == null || request.profile() == null) {
      throw new ProfileCatalogException("profile is required");
    }
    return catalog.updateDraft(draftId, request.profile(), request.actor());
  }

  @PostMapping("/drafts/{draftId}/publish")
  public ResponseEntity<ProfileRevision> publishDraft(@PathVariable String draftId, @RequestBody ActorRequest request) {
    requireRequest(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(catalog.publishDraft(draftId, request.actor()));
  }

  @GetMapping("/{profileId}")
  public ProfileRevision get(@PathVariable String profileId, @RequestParam(required = false) Integer revision) {
    return revision == null ? catalog.requireLatest(profileId) : catalog.require(profileId, revision);
  }

  @GetMapping("/{profileId}/history")
  public List<ProfileRevision> history(@PathVariable String profileId) {
    return catalog.history(profileId);
  }

  @PostMapping("/{profileId}/duplicate")
  public ResponseEntity<ProfileDraft> duplicate(
    @PathVariable String profileId,
    @RequestBody DuplicateProfileRequest request
  ) {
    requireRequest(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(
      catalog.duplicateDraft(profileId, request.sourceRevision(), request.displayName(), request.actor())
    );
  }

  @PostMapping("/{profileId}/update")
  public ResponseEntity<ProfileDraft> updateShared(
    @PathVariable String profileId,
    @RequestBody SourceRevisionRequest request
  ) {
    requireRequest(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(
      catalog.updateSharedDraft(profileId, request.sourceRevision(), request.actor())
    );
  }

  @PostMapping("/{profileId}/deactivate")
  public ProfileRevision deactivate(@PathVariable String profileId, @RequestBody ActorRequest request) {
    requireRequest(request);
    return catalog.deactivate(profileId, request.actor());
  }

  @PostMapping("/{profileId}/reactivate")
  public ProfileRevision reactivate(@PathVariable String profileId, @RequestBody ActorRequest request) {
    requireRequest(request);
    return catalog.reactivate(profileId, request.actor());
  }

  @ExceptionHandler(ProfileCatalogException.class)
  public ResponseEntity<Map<String, String>> handleCatalogError(ProfileCatalogException exception) {
    return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
  }

  private static void requireRequest(Object request) {
    if (request == null) {
      throw new ProfileCatalogException("request body is required");
    }
  }

  public record ProfileCatalogResponse(
    String schemaVersion,
    String catalogFingerprint,
    List<ProfileRevision> profiles
  ) {}

  public record CreateDraftRequest(String actor, String displayName) {}

  public record ProfileMutationRequest(String actor, ObjectNode profile) {}

  public record DuplicateProfileRequest(String actor, int sourceRevision, String displayName) {}

  public record SourceRevisionRequest(String actor, int sourceRevision) {}

  public record ActorRequest(String actor) {}
}
