package org.itech.ahb.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.itech.ahb.config.AnalyzerRegistryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
  void runtimeRegistryCanBeRead() throws Exception {
    mockMvc
      .perform(get("/api/analyzers"))
      .andExpect(status().isOk())
      .andExpect(content().json("{}"));
  }
}
