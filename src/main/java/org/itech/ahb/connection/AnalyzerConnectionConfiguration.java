package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.itech.ahb.profile.AnalyzerProfileCatalog;
import org.itech.ahb.connectivity.ConnectionProbeExecutor;
import org.itech.ahb.file.FileWatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AnalyzerConnectionCatalogProperties.class)
public class AnalyzerConnectionConfiguration {

  @Bean
  public AnalyzerConnectionCatalog analyzerConnectionCatalog(
    AnalyzerConnectionCatalogProperties properties,
    AnalyzerProfileCatalog profiles,
    ObjectMapper objectMapper,
    Clock profileCatalogClock,
    AnalyzerConnectionRuntime analyzerConnectionRuntime
  ) {
    return new AnalyzerConnectionCatalog(
      Path.of(properties.getDirectory()),
      profiles,
      objectMapper,
      profileCatalogClock,
      java.util.UUID::randomUUID,
      analyzerConnectionRuntime
    );
  }

  @Bean
  public AnalyzerConnectionRuntime analyzerConnectionRuntime(
    AnalyzerRuntimeRegistry registry,
    ObjectProvider<FileWatcher> fileWatcher,
    AstmConnectionListeners astmConnectionListeners,
    SerialConnectionListeners serialConnectionListeners
  ) {
    return new BridgeAnalyzerConnectionRuntime(
      registry,
      fileWatcher.getIfAvailable(),
      astmConnectionListeners,
      serialConnectionListeners
    );
  }

  @Bean
  public AnalyzerConnectionContractValidator analyzerConnectionContractValidator(ObjectMapper objectMapper) {
    return new AnalyzerConnectionContractValidator(objectMapper);
  }

  @Bean
  public AnalyzerConnectionProbe analyzerConnectionProbe(
    ObjectMapper objectMapper,
    Clock profileCatalogClock,
    ConnectionProbeExecutor executor
  ) {
    return new AnalyzerConnectionProbe(objectMapper, profileCatalogClock, executor);
  }
}
