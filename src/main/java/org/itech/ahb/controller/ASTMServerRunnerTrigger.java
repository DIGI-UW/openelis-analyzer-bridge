package org.itech.ahb.controller;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.lib.astm.servlet.ASTMServlet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Component for triggering an ASTM server runner for each ASTM transmission servlet.
 */
@Component
@ConditionalOnProperty(name = "org.itech.ahb.astm.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class ASTMServerRunnerTrigger {

  private ASTMServerRunner serverRunner;
  private List<ASTMServlet> astmServlets;

  /**
   * Constructor for ASTMServerRunnerTrigger.
   *
   * @param serverRunner the ASTM server runner
   * @param astmServlets the list of ASTM servlets that need to be run
   */
  public ASTMServerRunnerTrigger(ASTMServerRunner serverRunner, List<ASTMServlet> astmServlets) {
    this.serverRunner = serverRunner;
    this.astmServlets = astmServlets;
  }

  /**
   * Triggers the ASTM server runner to start the servlets once this class is done setting up.
   */
  @PostConstruct
  public void triggerRunner() {
    for (ASTMServlet astmServlet : astmServlets) {
      serverRunner.run(astmServlet);
    }
  }

  /**
   * Stops all ASTM servlets when the application context is shutting down.
   */
  @PreDestroy
  public void stopServers() {
    log.info("Shutting down ASTM servers...");
    for (ASTMServlet astmServlet : astmServlets) {
      try {
        astmServlet.stop();
      } catch (Exception e) {
        log.error("Error stopping ASTM server on port " + astmServlet.getListenPort(), e);
      }
    }
    log.info("All ASTM servers stopped");
  }
}
