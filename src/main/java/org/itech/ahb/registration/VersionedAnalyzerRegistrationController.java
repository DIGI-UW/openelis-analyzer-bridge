package org.itech.ahb.registration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analyzers")
public class VersionedAnalyzerRegistrationController {

  public static final String REQUEST_MEDIA_TYPE = "application/vnd.openelis.analyzer-registration.v1+json";
  public static final String RESPONSE_MEDIA_TYPE = "application/vnd.openelis.analyzer-registration-result.v1+json";

  private final RegistrationReconciliationService reconciliationService;

  public VersionedAnalyzerRegistrationController(RegistrationReconciliationService reconciliationService) {
    this.reconciliationService = reconciliationService;
  }

  @PutMapping(path = "/sync", consumes = REQUEST_MEDIA_TYPE, produces = RESPONSE_MEDIA_TYPE)
  public RegistrationSyncResult synchronize(@RequestBody JsonNode desiredState) {
    return reconciliationService.reconcile(desiredState);
  }

  @ExceptionHandler(RegistrationSyncException.class)
  public ResponseEntity<Map<String, String>> invalidDesiredState(RegistrationSyncException exception) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(
      Map.of("error", exception.getMessage())
    );
  }
}
