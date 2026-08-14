package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
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
public class PortableProfileController {

  private final PortableProfileCatalog catalog;

  public PortableProfileController(PortableProfileCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  public List<ProfileCatalogEntry> list(
    @RequestParam(required = false) String q,
    @RequestParam(required = false) String source,
    @RequestParam(required = false) String status,
    @RequestParam(required = false) String protocol
  ) {
    return catalog.list(new ProfileCatalogFilter(q, source, status, protocol));
  }

  @GetMapping("/{profileId}")
  public ProfileCatalogEntry get(@PathVariable String profileId, @RequestParam(required = false) Integer revision) {
    return revision == null ? catalog.requireLatest(profileId) : catalog.require(profileId, revision);
  }

  @GetMapping("/{profileId}/history")
  public List<ProfileCatalogEntry> history(@PathVariable String profileId) {
    return catalog.history(profileId);
  }

  @PostMapping
  public ResponseEntity<ProfileCatalogEntry> create(@RequestBody ProfileMutationRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createSite(request.profile(), request.actor()));
  }

  @PutMapping("/{profileId}")
  public ProfileCatalogEntry revise(@PathVariable String profileId, @RequestBody ProfileMutationRequest request) {
    return catalog.revise(profileId, request.profile(), request.actor());
  }

  @PostMapping("/{profileId}/fork")
  public ResponseEntity<ProfileCatalogEntry> fork(
    @PathVariable String profileId,
    @RequestBody ForkProfileRequest request
  ) {
    return ResponseEntity.status(HttpStatus.CREATED).body(
      catalog.fork(profileId, request.sourceRevision(), request.profileId(), request.displayName(), request.actor())
    );
  }

  @PostMapping("/{profileId}/deactivate")
  public ProfileCatalogEntry deactivate(@PathVariable String profileId, @RequestBody ActorRequest request) {
    return catalog.deactivate(profileId, request.actor());
  }

  @PostMapping("/{profileId}/reactivate")
  public ProfileCatalogEntry reactivate(@PathVariable String profileId, @RequestBody ActorRequest request) {
    return catalog.reactivate(profileId, request.actor());
  }

  @ExceptionHandler(ProfileCatalogException.class)
  public ResponseEntity<Map<String, String>> handleCatalogError(ProfileCatalogException exception) {
    return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
  }

  public record ProfileMutationRequest(String actor, JsonNode profile) {}

  public record ForkProfileRequest(String actor, int sourceRevision, String profileId, String displayName) {}

  public record ActorRequest(String actor) {}
}
