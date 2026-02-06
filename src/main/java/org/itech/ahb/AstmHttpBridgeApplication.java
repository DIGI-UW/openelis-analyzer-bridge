package org.itech.ahb;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.YamlPropertySourceFactory;
import org.itech.ahb.config.properties.ASTME138195ListenServerConfigurationProperties;
import org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties;
import org.itech.ahb.lib.astm.handling.ASTMHandler;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService.Mode;
import org.itech.ahb.lib.astm.interpretation.ASTMInterpreterFactory;
import org.itech.ahb.lib.astm.interpretation.DefaultASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet;
import org.itech.ahb.lib.astm.servlet.ASTMServlet.ASTMVersion;
import org.itech.ahb.normalizer.ASTMBridgeAdapter;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.util.StringUtils;

/**
 * Main application class for the ASTM HTTP Bridge. Starts the Spring Boot project and defines beans for the project.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@PropertySource(
  value = { "file:/app/configuration.yml", "classpath:application.yml" },
  ignoreResourceNotFound = true,
  factory = YamlPropertySourceFactory.class
)
@Slf4j
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

  /**
   * Bean for creating an ASTM servlet for LIS1-A.
   * <p>
   * After M7 refactoring, the servlet receives {@link ASTMHandlerService} via dependency
   * injection instead of calling the method directly.
   * </p>
   *
   * @param astmListenConfig the ASTM listen server configuration properties
   * @param astmHandlerService the ASTM handler service (injected)
   * @return the ASTM servlet
   */
  @Bean
  public ASTMServlet astmLIS01AServlet(
    ASTMLIS1AListenServerConfigurationProperties astmListenConfig,
    ASTMHandlerService astmHandlerService
  ) {
    log.info("creating astm server bean to handle incoming astm LIS1-A requests on port " + astmListenConfig.getPort());
    return new ASTMServlet(
      astmHandlerService,
      astmInterpreterFactory(),
      astmListenConfig.getPort(),
      ASTMVersion.LIS01_A
    );
  }

  /**
   * Bean for creating an ASTM servlet for E1381-95.
   * <p>
   * After M7 refactoring, the servlet receives {@link ASTMHandlerService} via dependency
   * injection instead of calling the method directly.
   * </p>
   *
   * @param astmListenConfig the ASTM listen server configuration properties
   * @param astmHandlerService the ASTM handler service (injected)
   * @return the ASTM servlet
   */
  @Bean
  public ASTMServlet astmE138195Servlet(
    ASTME138195ListenServerConfigurationProperties astmListenConfig,
    ASTMHandlerService astmHandlerService
  ) {
    log.info(
      "creating astm 1381-95 server bean to handle incoming astm 1381-95 requests on port " + astmListenConfig.getPort()
    );
    return new ASTMServlet(
      astmHandlerService,
      astmInterpreterFactory(),
      astmListenConfig.getPort(),
      ASTMVersion.E1381_95
    );
  }
}
