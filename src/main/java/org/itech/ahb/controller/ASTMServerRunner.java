package org.itech.ahb.controller;

import org.itech.ahb.lib.astm.servlet.ASTMServlet;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Wrapper class to run an ASTM transmission server asynchronously
 */
@Component
public class ASTMServerRunner {

  /**
   * Runs the given ASTM server.
   *
   * @param server the ASTM server to run
   */
  @Async
  public void run(ASTMServlet server) {
    server.listen();
  }
}
