package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

@Configuration
@EnableConfigurationProperties(ProfileCatalogProperties.class)
public class ProfileCatalogConfiguration {

  @Bean
  public Clock profileCatalogClock() {
    return Clock.systemUTC();
  }

  @Bean
  public AnalyzerProfileCatalog analyzerProfileCatalog(
    ProfileCatalogProperties properties,
    ResourcePatternResolver resourcePatternResolver,
    ObjectMapper objectMapper,
    Clock profileCatalogClock
  ) throws IOException {
    Resource[] resources = resourcePatternResolver.getResources(properties.getShippedPattern());
    List<Resource> shippedProfiles = Arrays.stream(resources)
      .sorted(Comparator.comparing(Resource::getDescription))
      .toList();
    return new AnalyzerProfileCatalog(
      Path.of(properties.getDirectory()),
      shippedProfiles,
      objectMapper,
      profileCatalogClock
    );
  }
}
