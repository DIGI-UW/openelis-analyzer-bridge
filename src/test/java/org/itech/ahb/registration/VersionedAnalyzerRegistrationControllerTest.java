package org.itech.ahb.registration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("OGC-1054 versioned registration HTTP contract")
class VersionedAnalyzerRegistrationControllerTest {

  private static final MediaType REQUEST_TYPE = MediaType.parseMediaType(
    "application/vnd.openelis.analyzer-registration.v1+json"
  );
  private static final MediaType RESPONSE_TYPE = MediaType.parseMediaType(
    "application/vnd.openelis.analyzer-registration-result.v1+json"
  );

  private MockMvc mockMvc;
  private RegistrationReconciliationService service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    service = mock(RegistrationReconciliationService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new VersionedAnalyzerRegistrationController(service))
      .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
      .build();
  }

  @Test
  @DisplayName("vendor-versioned desired state returns the published reconciliation shape")
  void reconcilesVersionedDesiredState() throws Exception {
    when(service.reconcile(any())).thenReturn(
      new RegistrationSyncResult(
        "1.0",
        "sha256:registration-initial",
        new RegistrationSyncResult.Counts(1, 1, 0, 0, 0, 0),
        List.of(new RegistrationSyncResult.Registration("42", RegistrationSyncResult.Status.APPLIED, null)),
        List.of()
      )
    );

    mockMvc
      .perform(put("/api/analyzers/sync").contentType(REQUEST_TYPE).accept(RESPONSE_TYPE).content("{}"))
      .andExpect(status().isOk())
      .andExpect(content().contentTypeCompatibleWith(RESPONSE_TYPE))
      .andExpect(jsonPath("$.schemaVersion").value("1.0"))
      .andExpect(jsonPath("$.appliedStateRevision").value("sha256:registration-initial"))
      .andExpect(jsonPath("$.counts.added").value(1))
      .andExpect(jsonPath("$.registrations[0].status").value("APPLIED"))
      .andExpect(jsonPath("$.registrations[0].message").doesNotExist())
      .andExpect(jsonPath("$.errors").isEmpty());
  }

  @Test
  @DisplayName("schema failures are visible client errors")
  void reportsSchemaFailure() throws Exception {
    when(service.reconcile(any())).thenThrow(new RegistrationSyncException("Registration sync violates schema"));

    mockMvc
      .perform(put("/api/analyzers/sync").contentType(REQUEST_TYPE).accept(RESPONSE_TYPE).content("{}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error").value("Registration sync violates schema"));
  }
}
