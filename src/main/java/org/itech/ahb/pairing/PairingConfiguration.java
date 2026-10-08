package org.itech.ahb.pairing;

import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Configuration
@Slf4j
public class PairingConfiguration {

  private final PairingState pairing;

  public PairingConfiguration(
    @Value("${bridge.identity.directory:" + BridgeIdentityEnvironment.DEFAULT_DIRECTORY + "}") String directory,
    @Value("${bridge.pairing.code:}") String code
  ) {
    this.pairing = new PairingState(Path.of(directory), code);
  }

  @Bean
  public PairingState pairingState() {
    return pairing;
  }

  /** The identity HTTPS serves, which is also the certificate the Bridge presents to OpenELIS. */
  @Bean
  @ConditionalOnProperty(name = "server.ssl.enabled", havingValue = "true")
  public BridgeIdentity bridgeIdentity(
    @Value("${server.ssl.key-store}") String keystore,
    @Value("${server.ssl.key-store-type:PKCS12}") String type,
    @Value("${server.ssl.key-store-password:}") String password
  ) {
    String path = keystore.startsWith("file:") ? keystore.substring("file:".length()) : keystore;
    return BridgeIdentity.load(new BridgeIdentity.Keystore(Path.of(path), type, password.toCharArray()));
  }

  /** Ask clients for a certificate without requiring one: health, pairing and HTTP input need none. */
  @Bean
  @ConditionalOnProperty(name = "server.ssl.enabled", havingValue = "true")
  public WebServerFactoryCustomizer<TomcatServletWebServerFactory> clientCertificates() {
    return factory ->
      factory.addConnectorCustomizers(connector -> {
        for (SSLHostConfig host : connector.findSslHostConfigs()) {
          host.setCertificateVerification(SSLHostConfig.CertificateVerification.OPTIONAL.name());
          host.setTrustManagerClassName(AnyClientCertificateTrustManager.class.getName());
        }
      });
  }

  @EventListener(ApplicationReadyEvent.class)
  public void announce() {
    if (pairing.isPaired()) {
      log.info("Bridge is paired with OpenELIS since {}", pairing.pairedAt().orElseThrow());
      if (pairing.isOpen()) {
        log.warn("A new pairing code is configured: the next OpenELIS to pair with it replaces the current one");
      }
      return;
    }
    pairing
      .generatedCode()
      .ifPresentOrElse(
        code ->
          log.warn(
            "Bridge is not paired with OpenELIS. Enter this pairing code in OpenELIS to pair it: {}. " +
            "A new code is generated at each start until pairing succeeds.",
            code
          ),
        () -> log.warn("Bridge is not paired with OpenELIS; pair it with the configured pairing code")
      );
  }
}
