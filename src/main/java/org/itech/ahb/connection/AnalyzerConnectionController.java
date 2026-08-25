package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.itech.ahb.connection.AnalyzerConnectionException.Kind;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/connections")
public final class AnalyzerConnectionController {

  private final AnalyzerConnectionCatalog connections;
  private final AnalyzerConnectionContractValidator contracts;
  private final AnalyzerConnectionProbe probe;

  public AnalyzerConnectionController(
    AnalyzerConnectionCatalog connections,
    AnalyzerConnectionContractValidator contracts,
    AnalyzerConnectionProbe probe
  ) {
    this.connections = connections;
    this.contracts = contracts;
    this.probe = probe;
  }

  @PostMapping
  public ResponseEntity<ObjectNode> create(@RequestBody ObjectNode request) {
    contracts.validateCreate(request);
    ObjectNode response = connections.create(request);
    contracts.validateResponse(response);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/{connectionId}")
  public ObjectNode get(@PathVariable String connectionId) {
    ObjectNode response = connections.require(connectionId);
    contracts.validateResponse(response);
    return response;
  }

  @PutMapping("/{connectionId}")
  public ObjectNode update(@PathVariable String connectionId, @RequestBody ObjectNode request) {
    contracts.validateUpdate(request);
    if (!connectionId.equals(request.path("connectionId").asText())) {
      throw new AnalyzerConnectionException("Path connectionId does not match request connectionId");
    }
    ObjectNode response = connections.update(request);
    contracts.validateResponse(response);
    return response;
  }

  @PostMapping("/{connectionId}/probe")
  public ObjectNode probe(@PathVariable String connectionId, @RequestBody ObjectNode request) {
    contracts.validateProbeRequest(request);
    if (!connectionId.equals(request.path("connectionId").asText())) {
      throw new AnalyzerConnectionException("Path connectionId does not match request connectionId");
    }
    ObjectNode result = connections.probe(request, probe);
    contracts.validateProbeResult(result);
    return result;
  }

  @PostMapping("/{connectionId}/runtime")
  public ObjectNode applyRuntimeCommand(@PathVariable String connectionId, @RequestBody ObjectNode command) {
    contracts.validateRuntimeCommand(command);
    if (!connectionId.equals(command.path("connectionId").asText())) {
      throw new AnalyzerConnectionException("Path connectionId does not match request connectionId");
    }
    ObjectNode acknowledgement = connections.applyRuntimeCommand(command);
    contracts.validateRuntimeAcknowledgement(acknowledgement);
    return acknowledgement;
  }

  @ExceptionHandler(AnalyzerConnectionException.class)
  public ResponseEntity<Map<String, String>> handleConnectionError(AnalyzerConnectionException exception) {
    HttpStatus status = switch (exception.kind()) {
      case NOT_FOUND -> HttpStatus.NOT_FOUND;
      case CONFLICT -> HttpStatus.CONFLICT;
      case INVALID -> HttpStatus.BAD_REQUEST;
    };
    return ResponseEntity.status(status).body(Map.of("error", exception.getMessage()));
  }
}
