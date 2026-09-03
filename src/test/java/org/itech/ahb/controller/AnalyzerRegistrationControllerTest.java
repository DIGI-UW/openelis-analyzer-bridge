package org.itech.ahb.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AnalyzerRegistrationControllerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    AnalyzerRegistrationController controller = new AnalyzerRegistrationController(new AnalyzerRegistryConfig());
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void registrationResourceIsReadOnly() throws Exception {
    mockMvc.perform(get("/api/analyzers")).andExpect(status().isOk());
    mockMvc
      .perform(put("/api/analyzers/sync").contentType(MediaType.APPLICATION_JSON).content("{}"))
      .andExpect(status().isNotFound());
    mockMvc
      .perform(post("/api/analyzers/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
      .andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/analyzers/42")).andExpect(status().isNotFound());
  }
}
