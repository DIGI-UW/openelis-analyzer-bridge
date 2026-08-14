package org.itech.ahb.profile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PortableProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
class PortableProfileControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private PortableProfileCatalog catalog;

  private ProfileCatalogEntry entry;

  @BeforeEach
  void setUp() throws Exception {
    entry = new ProfileCatalogEntry(
      objectMapper.readTree(
        """
        {
          "schemaVersion": "1.0",
          "profileId": "genexpert-astm",
          "revision": 1,
          "displayName": "GeneXpert ASTM",
          "source": "SHIPPED",
          "status": "ACTIVE",
          "protocol": "ASTM",
          "capabilities": {
            "inboundResults": true,
            "outboundOrders": false,
            "connectionTest": true
          },
          "tests": [],
          "qcIdentification": []
        }
        """
      ),
      new ProfileAuditEvent(ProfileAuditAction.SHIPPED, "distribution", Instant.parse("2026-08-14T02:00:00Z")),
      "sha256:abc123"
    );
  }

  @Test
  void listsProfilesWithUrlBackedFilters() throws Exception {
    when(catalog.list(any())).thenReturn(List.of(entry));

    mockMvc
      .perform(
        get("/api/profiles")
          .queryParam("q", "gene")
          .queryParam("source", "SHIPPED")
          .queryParam("status", "ACTIVE")
          .queryParam("protocol", "ASTM")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].profile.profileId").value("genexpert-astm"))
      .andExpect(jsonPath("$[0].audit.actor").value("distribution"))
      .andExpect(jsonPath("$[0].fingerprint").value("sha256:abc123"));

    ArgumentCaptor<ProfileCatalogFilter> filter = ArgumentCaptor.forClass(ProfileCatalogFilter.class);
    verify(catalog).list(filter.capture());
    org.assertj.core.api.Assertions.assertThat(filter.getValue()).isEqualTo(
      new ProfileCatalogFilter("gene", "SHIPPED", "ACTIVE", "ASTM")
    );
  }

  @Test
  void readsAnExactRevisionAndItsImmutableHistory() throws Exception {
    when(catalog.require("genexpert-astm", 1)).thenReturn(entry);
    when(catalog.history("genexpert-astm")).thenReturn(List.of(entry));

    mockMvc
      .perform(get("/api/profiles/genexpert-astm").queryParam("revision", "1"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.profile.revision").value(1));
    mockMvc
      .perform(get("/api/profiles/genexpert-astm/history"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].audit.action").value("SHIPPED"));
  }

  @Test
  void createsAndRevisesSiteProfilesWithAnExplicitActor() throws Exception {
    when(catalog.createSite(any(), eq("oe-user"))).thenReturn(entry);
    when(catalog.revise(eq("genexpert-astm"), any(), eq("oe-user"))).thenReturn(entry);
    String body =
      """
      {
        "actor": "oe-user",
        "profile": {
          "schemaVersion": "1.0",
          "profileId": "site-profile",
          "revision": 1,
          "displayName": "Site profile",
          "source": "SITE",
          "status": "ACTIVE",
          "protocol": "ASTM",
          "capabilities": {
            "inboundResults": true,
            "outboundOrders": false,
            "connectionTest": true
          },
          "tests": [],
          "qcIdentification": []
        }
      }
      """;

    mockMvc
      .perform(post("/api/profiles").contentType(MediaType.APPLICATION_JSON).content(body))
      .andExpect(status().isCreated());
    mockMvc
      .perform(put("/api/profiles/genexpert-astm").contentType(MediaType.APPLICATION_JSON).content(body))
      .andExpect(status().isOk());
  }

  @Test
  void forksAndChangesLifecycleWithoutExposingHardDelete() throws Exception {
    when(catalog.fork("genexpert-astm", 1, "site-genexpert", "Site GeneXpert", "oe-admin")).thenReturn(entry);
    when(catalog.deactivate("genexpert-astm", "oe-admin")).thenReturn(entry);
    when(catalog.reactivate("genexpert-astm", "oe-admin")).thenReturn(entry);

    mockMvc
      .perform(
        post("/api/profiles/genexpert-astm/fork")
          .contentType(MediaType.APPLICATION_JSON)
          .content(
            """
            {
              "actor": "oe-admin",
              "sourceRevision": 1,
              "profileId": "site-genexpert",
              "displayName": "Site GeneXpert"
            }
            """
          )
      )
      .andExpect(status().isCreated());
    mockMvc
      .perform(
        post("/api/profiles/genexpert-astm/deactivate")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"actor\":\"oe-admin\"}")
      )
      .andExpect(status().isOk());
    mockMvc
      .perform(
        post("/api/profiles/genexpert-astm/reactivate")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"actor\":\"oe-admin\"}")
      )
      .andExpect(status().isOk());
    mockMvc.perform(delete("/api/profiles/genexpert-astm")).andExpect(status().isMethodNotAllowed());
  }
}
