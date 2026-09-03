package org.itech.ahb.controller;

import java.util.Map;
import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the current runtime registry. Durable connection writes use the connection API. */
@RestController
@RequestMapping("/api/analyzers")
public final class AnalyzerRegistrationController {

  private final AnalyzerRegistryConfig registry;

  public AnalyzerRegistrationController(AnalyzerRegistryConfig registry) {
    this.registry = registry;
  }

  @GetMapping
  public ResponseEntity<Map<String, AnalyzerEntry>> list() {
    return ResponseEntity.ok(registry.getRegisteredAnalyzers());
  }
}
