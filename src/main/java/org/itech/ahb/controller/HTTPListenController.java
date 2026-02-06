package org.itech.ahb.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.properties.ASTMForwardServerConfigurationProperties;
import org.itech.ahb.lib.astm.concept.ASTMMessage;
import org.itech.ahb.lib.astm.handling.ASTMHandlerService;
import org.itech.ahb.lib.astm.interpretation.ASTMInterpreterFactory;
import org.itech.ahb.lib.astm.servlet.ASTMServlet.ASTMVersion;
import org.itech.ahb.lib.common.handling.HandleStatus;
import org.itech.ahb.lib.http.handling.DefaultForwardingHTTPToASTMHandler;
import org.itech.ahb.lib.http.handling.HTTPForwardingHandlerInfo;
import org.itech.ahb.lib.http.handling.HTTPHandler;
import org.itech.ahb.lib.http.handling.HTTPHandlerResponse;
import org.itech.ahb.lib.http.handling.HTTPHandlerService;
import org.itech.ahb.lib.http.handling.HTTPHandlerService.Mode;
import org.itech.ahb.lib.http.handling.HTTPHandlerServiceResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for receiving ASTM messages over HTTP.
 */
@RestController
@RequestMapping("/")
@Slf4j
public class HTTPListenController {

  private final HTTPHandlerService httpHandlerService;
  private final ASTMInterpreterFactory interpreterFactory;

  /**
   * Constructor for HTTPListenController.
   *
   * @param interpreterFactory the ASTM interpreter factory
   * @param astmForwardConfig the configuration properties for where to forward the ASTM messages
   * @param astmHandlerService the ASTM handler service that will call the relevant handler(s)
   */
  public HTTPListenController(
    ASTMInterpreterFactory interpreterFactory,
    ASTMForwardServerConfigurationProperties astmForwardConfig,
    ASTMHandlerService astmHandlerService
  ) {
    List<HTTPHandler> httpHandlers = Arrays.asList(
      new DefaultForwardingHTTPToASTMHandler(
        astmForwardConfig.getHostName(),
        astmForwardConfig.getPort(),
        astmHandlerService,
        interpreterFactory
      )
    );
    this.interpreterFactory = interpreterFactory;
    this.httpHandlerService = new HTTPHandlerService(httpHandlers, Mode.FIRST);
  }

  /**
   * Receives ASTM messages over HTTP and forwards them.
   *
   * @param requestBody the request body containing the ASTM message
   * @param forwardAddress the address to forward the message to.  Leaving blank will use the default configured forward address.
   * @param forwardPort the port to forward the message to. Leaving blank will use the default configured forward port.
   * @param forwardAstmVersion the ASTM transmission protocol to forward the message over.
   * @param response the HTTP servlet response
   * @return the HTTP service response ie. the responses of the individual handlers that handled the message.
   */
  @PostMapping
  public HTTPHandlerServiceResponse recieveASTMMessageOverHttp(
    @RequestBody(required = false) String requestBody,
    @RequestParam(required = false) String forwardAddress,
    @RequestParam(required = false, defaultValue = "0") Integer forwardPort,
    @RequestParam(required = false, defaultValue = "LIS01_A") ASTMVersion forwardAstmVersion,
    HttpServletResponse response
  ) {
    log.debug("received http request to handle");
    log.trace("requestBody length: " + (requestBody != null ? requestBody.length() : 0));
    log.trace("forwardAddress: " + forwardAddress);
    log.trace("forwardPort: " + forwardPort);
    log.trace("forwardAstmVersion: " + forwardAstmVersion);
    ASTMMessage message = interpreterFactory
      .createInterpreterForText(requestBody)
      .interpretASTMTextToMessage(requestBody);
    HTTPForwardingHandlerInfo handlerInfo = new HTTPForwardingHandlerInfo();
    handlerInfo.setForwardAddress(forwardAddress);
    handlerInfo.setForwardPort(forwardPort);
    handlerInfo.setForwardAstmVersion(forwardAstmVersion);
    HTTPHandlerServiceResponse serviceResponse = httpHandlerService.handle(message, Set.of(handlerInfo));
    if (serviceResponse.getResponses() == null || serviceResponse.getResponses().size() == 0) {
      log.error("message was unhandled");
    } else {
      for (HTTPHandlerResponse handlerResponse : serviceResponse.getResponses()) {
        if (handlerResponse.getStatus() != HandleStatus.SUCCESS) {
          log.error("message was not handled successfully by: " + handlerResponse.getHandler().getName());
        } else {
          log.debug("message was handled successfully by: " + handlerResponse.getHandler().getName());
        }
      }
    }
    return serviceResponse;
  }
}
