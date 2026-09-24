package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.itech.ahb.config.properties.ASTME138195ListenServerConfigurationProperties;
import org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties;
import org.itech.ahb.connectivity.ConnectionProbeExecutor;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.mllp.MLLPConfig;
import org.itech.ahb.profile.AnalyzerProfileCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ AnalyzerConnectionCatalogProperties.class, AnalyzerOutboundDefaults.class })
public class AnalyzerConnectionConfiguration {

  @Bean
  public AnalyzerOutboundEndpoint analyzerOutboundEndpoint(AnalyzerOutboundDefaults defaults) {
    return new AnalyzerOutboundEndpoint(defaults);
  }

  @Bean
  public AnalyzerListenerPorts analyzerListenerPorts(
    ASTMLIS1AListenServerConfigurationProperties lis1a,
    ASTME138195ListenServerConfigurationProperties e138195,
    MLLPConfig mllp
  ) {
    return new AnalyzerListenerPorts(lis1a, e138195, mllp);
  }

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
    Hl7ConnectionListeners hl7ConnectionListeners,
    AnalyzerListenerPorts listenerPorts,
    AnalyzerOutboundEndpoint outboundEndpoint
  ) {
    return new BridgeAnalyzerConnectionRuntime(
      registry,
      fileWatcher.getIfAvailable(),
      astmConnectionListeners,
      serialConnectionListeners,
      hl7ConnectionListeners,
      listenerPorts,
      outboundEndpoint
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
    Hl7ConnectionListeners hl7ConnectionListeners,
    AnalyzerListenerPorts listenerPorts,
    AnalyzerOutboundEndpoint outboundEndpoint
  ) {
    return new AnalyzerConnectionProbe(
      objectMapper,
      profileCatalogClock,
      executor,
      (protocol, port) ->
        switch (protocol) {
          case "ASTM" -> astmConnectionListeners.isListening(port);
          case "HL7" -> hl7ConnectionListeners.isListening(port);
          default -> false;
        },
      listenerPorts,
      outboundEndpoint
    );
  }
}
