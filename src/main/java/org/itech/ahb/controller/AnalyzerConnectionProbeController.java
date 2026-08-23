package org.itech.ahb.controller;

import java.util.Map;
import org.itech.ahb.connectivity.AnalyzerConnectionProbeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes connection evidence for one registered OpenELIS analyzer. */
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
  public ResponseEntity<?> probe(@PathVariable String analyzerId) {
    return service
      .probe(analyzerId)
      .<ResponseEntity<?>>map(ResponseEntity::ok)
      .orElseGet(() ->
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
          Map.of(
            "error",
            "analyzer_not_registered",
            "analyzerId",
            analyzerId
          )
        )
      );
  }
}
