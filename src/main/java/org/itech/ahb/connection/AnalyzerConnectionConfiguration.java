package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.itech.ahb.connectivity.ConnectionProbeExecutor;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.profile.AnalyzerProfileCatalog;
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
    SerialConnectionListeners serialConnectionListeners,
    Hl7ConnectionListeners hl7ConnectionListeners
  ) {
    return new BridgeAnalyzerConnectionRuntime(
      registry,
      fileWatcher.getIfAvailable(),
      astmConnectionListeners,
      serialConnectionListeners,
      hl7ConnectionListeners
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
    ConnectionProbeExecutor executor,
    AstmConnectionListeners astmConnectionListeners,
    Hl7ConnectionListeners hl7ConnectionListeners
  ) {
    return new AnalyzerConnectionProbe(
      objectMapper,
      profileCatalogClock,
      executor,
      (protocol, port) -> switch (protocol) {
        case "ASTM" -> astmConnectionListeners.isListening(port);
        case "HL7" -> hl7ConnectionListeners.isListening(port);
        default -> false;
      }
    );
  }
}
