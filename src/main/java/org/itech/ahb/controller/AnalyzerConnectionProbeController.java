package org.itech.ahb.controller;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.itech.ahb.connectivity.AnalyzerConnectionProbeService;
import org.itech.ahb.registration.RegistrationContractException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes connection evidence for one transient OpenELIS analyzer candidate. */
@RestController
@RequestMapping("/api/analyzers")
public final class AnalyzerConnectionProbeController {

  private final AnalyzerConnectionProbeService service;

  public AnalyzerConnectionProbeController(
    AnalyzerConnectionProbeService service
  ) {
    this.service = service;
  }

  @PostMapping("/{analyzerId}/probe")
  public ResponseEntity<?> probe(
    @PathVariable String analyzerId,
    @RequestBody JsonNode candidate
  ) {
    return ResponseEntity.ok(service.probe(analyzerId, candidate));
  }

  @ExceptionHandler(RegistrationContractException.class)
  public ResponseEntity<Map<String, String>> handleContractError(
    RegistrationContractException exception
  ) {
    return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
  }
}
