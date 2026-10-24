package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/** A profile packaged with the Bridge, as the catalog serves it: loaded through the shipped path, fingerprints derived. */
public final class PackagedProfiles {

  private static final Path NO_PERSISTED_REVISIONS = Path.of("target", "packaged-profiles-catalog");

  private PackagedProfiles() {}

  public static ObjectNode load(ObjectMapper objectMapper, String profileId) {
    AnalyzerProfileCatalog catalog = new AnalyzerProfileCatalog(
      NO_PERSISTED_REVISIONS,
      List.of(new ClassPathResource("analyzer-profiles/" + profileId + ".json")),
      objectMapper,
      Clock.systemUTC()
    );
    if (!catalog.issues().isEmpty()) {
      throw new IllegalStateException(profileId + " did not load: " + catalog.issues());
    }
    return catalog.requireLatest(profileId).profile().deepCopy();
  }
}
