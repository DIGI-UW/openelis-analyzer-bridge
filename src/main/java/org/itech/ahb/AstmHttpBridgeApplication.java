package org.itech.ahb;

import java.util.Arrays;
import java.util.List;
import org.itech.ahb.config.YamlPropertySourceFactory;
import org.itech.ahb.lib.astm.handling.ASTMHandler;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService.Mode;
import org.itech.ahb.lib.astm.interpretation.ASTMInterpreterFactory;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.normalizer.ASTMBridgeAdapter;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;

/**
 * Main application class for the ASTM HTTP Bridge. Starts the Spring Boot project and defines beans for the project.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@PropertySource(
  value = { "file:/app/configuration.yml", "classpath:application.yml" },
  ignoreResourceNotFound = true,
  factory = YamlPropertySourceFactory.class
)
public class AstmHttpBridgeApplication {

  /**
   * Main method to run the application.
   *
   * @param args the command line arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(AstmHttpBridgeApplication.class, args);
  }

  /**
   * Bean for creating an ASTM interpreter factory.
   *
   * @return the ASTM interpreter factory
   */
  @Bean
  public ASTMInterpreterFactory astmInterpreterFactory() {
    return new DefaultASTMInterpreterFactory();
  }

  /**
   * Bean for creating an ASTM handler service.
   * <p>
   * After M7 (Message Normalizer) refactoring, the handler service uses
   * {@link ASTMBridgeAdapter} instead of {@code DefaultForwardingASTMToHTTPHandler}.
   * The adapter delegates to {@link MessageNormalizer} for unified routing,
   * retry/backoff, and audit logging.
   * </p>
   *
   * @param normalizer the message normalizer for routing
   * @return the ASTM handler service
   */
  @Bean
  public ASTMHandlerService astmHandlerService(MessageNormalizer normalizer) {
    List<ASTMHandler> astmHandlers = Arrays.asList(
      new ASTMBridgeAdapter(normalizer)
    );
    return new ASTMHandlerService(astmHandlers, Mode.FIRST);
  }
}
