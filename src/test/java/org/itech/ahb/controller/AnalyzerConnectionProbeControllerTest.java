package org.itech.ahb.controller;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.itech.ahb.connectivity.AnalyzerConnectionProbeService;
import org.itech.ahb.registration.RegistrationContractException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AnalyzerConnectionProbeControllerTest {

  @Mock
  AnalyzerConnectionProbeService service;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(
      new AnalyzerConnectionProbeController(service)
    ).build();
  }

  @Test
  void probesOneStrictCandidateWithoutApplyingItToRuntime() throws Exception {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.put("schemaVersion", "1.0");
    result.put("analyzerId", "77");
    when(service.probe(eq("77"), any(JsonNode.class))).thenReturn(result);

    mockMvc
      .perform(
        post("/api/analyzers/77/probe")
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .content("{}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.schemaVersion").value("1.0"))
      .andExpect(jsonPath("$.analyzerId").value("77"));
  }

  @Test
  void invalidCandidateIsVisibleAsBadRequest()
    throws Exception {
    when(service.probe(eq("77"), any(JsonNode.class)))
      .thenThrow(new RegistrationContractException("profileRef is required"));

    mockMvc
      .perform(
        post("/api/analyzers/77/probe")
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .content("{}")
      )
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error").value("profileRef is required"));
  }
}
