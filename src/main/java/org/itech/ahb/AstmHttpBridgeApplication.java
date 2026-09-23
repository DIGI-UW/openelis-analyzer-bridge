package org.itech.ahb;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.YamlPropertySourceFactory;
import org.itech.ahb.config.properties.ASTME138195ListenServerConfigurationProperties;
import org.itech.ahb.config.properties.ASTMLIS1AListenServerConfigurationProperties;
import org.itech.ahb.connection.ManagedAstmConnectionListeners;
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
   * The handler service uses {@link ASTMBridgeAdapter}, which delegates to
   * {@link MessageNormalizer} for profile-driven parsing and normalized routing.
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
   * Bean for the shared ASTM LIS1-A listener, bound at boot whether or not any analyzer connection exists.
   * <p>
   * The servlet is owned by {@link ManagedAstmConnectionListeners}, so a saved connection that declares this
   * port joins this listener instead of binding a second socket.
   * </p>
   *
   * @param astmListenConfig the ASTM listen server configuration properties
   * @param listeners the owner of every ASTM server socket
   * @return the ASTM servlet
   */
  @Bean
  public ASTMServlet astmLIS01AServlet(
    ASTMLIS1AListenServerConfigurationProperties astmListenConfig,
    ManagedAstmConnectionListeners listeners
  ) {
    log.info("creating astm server bean to handle incoming astm LIS1-A requests on port " + astmListenConfig.getPort());
    return listeners.holdBootListener(astmListenConfig.getPort(), ASTMVersion.LIS01_A.name());
  }

  /**
   * Bean for the shared ASTM E1381-95 listener, bound at boot whether or not any analyzer connection exists.
   *
   * @param astmListenConfig the ASTM listen server configuration properties
   * @param listeners the owner of every ASTM server socket
   * @return the ASTM servlet
   */
  @Bean
  public ASTMServlet astmE138195Servlet(
    ASTME138195ListenServerConfigurationProperties astmListenConfig,
    ManagedAstmConnectionListeners listeners
  ) {
    log.info(
      "creating astm 1381-95 server bean to handle incoming astm 1381-95 requests on port " + astmListenConfig.getPort()
    );
    return listeners.holdBootListener(astmListenConfig.getPort(), ASTMVersion.E1381_95.name());
  }
}
