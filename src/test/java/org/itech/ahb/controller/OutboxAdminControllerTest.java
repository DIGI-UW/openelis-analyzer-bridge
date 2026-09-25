package org.itech.ahb.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.outbox.FailureReason;
import org.itech.ahb.outbox.OutboxState;
import org.itech.ahb.outbox.OutboxStore;
import org.itech.ahb.outbox.Receipt;
import org.itech.ahb.outbox.ReceivedMessage;
import org.itech.ahb.outbox.RenderedDelivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The operator's view of held results, exercised through the real security chain and a real store.
 *
 * <p>Two properties matter beyond the JSON shape: an operator can always get a held result moving
 * again, and patient data never appears anywhere except the one endpoint that logs who read it.
 */
@SpringBootTest(
  properties = {
    "bridge.security.enabled=true",
    "bridge.security.username=testuser",
    "bridge.security.password=testpass",
    "org.itech.ahb.mllp.enabled=false",
    "bridge.file.enabled=false"
  }
)
@AutoConfigureMockMvc
class OutboxAdminControllerTest {

  private static final String RAW = "H|\\^&|||GeneXpert\rP|1\rO|1|ACC-1\rR|1|^^^MTB|NEG||||F\rL|1|N\r";

  @DynamicPropertySource
  static void outboxLocation(DynamicPropertyRegistry registry) throws IOException {
    Path directory = Files.createTempDirectory("bridge-outbox-admin-test");
    registry.add("bridge.outbox.db-path", () -> directory.resolve("outbox.db").toString());
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OutboxStore store;

  @Autowired
  private org.itech.ahb.outbox.OutboxDispatcher dispatcher;

  @MockBean
  private FileWatcher fileWatcher;

  private String deadLetteredId;
  private String deliveredId;
  private String raw;

  @Test
  void binaryFilePayloadRequiresAuthenticationAndDownloadsExactBytes() throws Exception {
    byte[] bytes = { 0, (byte) 255, 13, 10, 1 };
    var receipt = store.receiveFile(
      new org.itech.ahb.outbox.ReceivedFile(
        bytes,
        "/binary.xlsx",
        "file-" + java.util.UUID.randomUUID(),
        "oe-file",
        "profile",
        1,
        "{\"version\":1}",
        "parse"
      )
    );
    mockMvc.perform(get("/admin/outbox/" + receipt.id() + "/raw-file")).andExpect(status().isUnauthorized());
    mockMvc
      .perform(get("/admin/outbox/" + receipt.id() + "/raw-file").with(httpBasic("testuser", "testpass")))
      .andExpect(status().isOk())
      .andExpect(content().bytes(bytes));
    mockMvc
      .perform(get("/admin/outbox/" + receipt.id() + "/payload").with(httpBasic("testuser", "testpass")))
      .andExpect(status().isOk())
      .andExpect(content().string(java.util.Base64.getEncoder().encodeToString(bytes)))
      .andExpect(
        org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
          .string("X-Bridge-Payload-Encoding", "BASE64")
      );
  }

  @Test
  void incompleteFramesAreVisibleButNeitherSingleNorBulkRetryCanDeliverThem() throws Exception {
    String id = "astm-session:" + java.util.UUID.randomUUID();
    byte[] bytes = { 2, 49, 72, 124, 23, 48, 48, 13, 10 };
    store.receiveAstmFrames(
      id,
      new ReceivedMessage("192.0.2.10", 51000, Protocol.ASTM, Transport.TCP, null, null, null, Instant.now(), 1200),
      bytes
    );
    mockMvc.perform(get("/admin/outbox/" + id + "/astm-frames")).andExpect(status().isUnauthorized());
    mockMvc
      .perform(get("/admin/outbox/" + id + "/astm-frames").with(httpBasic("testuser", "testpass")))
      .andExpect(status().isOk())
      .andExpect(content().bytes(bytes));
    mockMvc
      .perform(get("/admin/outbox/" + id).with(httpBasic("testuser", "testpass")))
      .andExpect(jsonPath("$.failureReason").value("INCOMPLETE_TRANSMISSION"));
    mockMvc
      .perform(post("/admin/outbox/" + id + "/retry").with(httpBasic("testuser", "testpass")))
      .andExpect(status().isConflict());
    mockMvc
      .perform(
        post("/admin/outbox/retry")
          .with(httpBasic("testuser", "testpass"))
          .contentType("application/json")
          .content("{\"ids\":[\"" + id + "\"]}")
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.retried").value(0));
    org.junit.jupiter.api.Assertions.assertEquals(
      FailureReason.INCOMPLETE_TRANSMISSION,
      store.get(id).orElseThrow().failureReason()
    );
  }

  @BeforeEach
  void seed() {
    // The context starts the real dispatcher, which would claim these entries and race the
    // assertions. These tests are about the operator API, so hold delivery still.
    dispatcher.stop();

    // Fresh identities per test: the store is shared across methods in this context, and content
    // derives the identity, so each test seeds content of its own.
    String unique = java.util.UUID.randomUUID().toString();
    raw = RAW + "C|1|" + unique + "\r";
    deadLetteredId = "astm-v1:dmq-" + unique;
    deliveredId = "astm-v1:delivered-" + unique;

    Receipt first = store.receive(
      new ReceivedMessage("10.0.0.1", 12001, Protocol.ASTM, Transport.TCP, "GeneXpert", raw, null, Instant.now())
    );
    store.markRendered(first.id(), List.of(delivery(deadLetteredId, "ACC-1")));
    store.markRejected(deadLetteredId, FailureReason.RETRY_EXHAUSTED, null, "java.net.UnknownHostException: oe", null);

    Receipt second = store.receive(
      new ReceivedMessage("10.0.0.2", 12001, Protocol.ASTM, Transport.TCP, "GeneXpert", raw + "\r", null, Instant.now())
    );
    store.markRendered(second.id(), List.of(delivery(deliveredId, "ACC-2")));
    store.markDelivered(deliveredId, 200, "rcpt-1", "{\"receiptId\":\"rcpt-1\"}");
  }

  private static RenderedDelivery delivery(String id, String accession) {
    return new RenderedDelivery(
      id,
      accession,
      "{\"resourceType\":\"Bundle\",\"identifier\":{\"value\":\"" + id + "\"}}",
      "bridge-connection-7f3c",
      "analyzer-1",
      "genexpert-astm",
      1,
      "https://oe:8443/api/OpenELIS-Global/analyzer/fhir"
    );
  }

  @Test
  @DisplayName("held results are not readable without credentials")
  void requiresAuthentication() throws Exception {
    mockMvc.perform(get("/admin/outbox")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/admin/outbox/stats")).andExpect(status().isUnauthorized());
    mockMvc.perform(post("/admin/outbox/" + deadLetteredId + "/retry")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/admin/outbox/" + deadLetteredId + "/payload")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("listing shows what an operator triages by, and never the result itself")
  void listingCarriesTriageFactsButNoPayload() throws Exception {
    mockMvc
      .perform(
        get("/admin/outbox")
          .param("state", "DMQ")
          .param("connectionId", "bridge-connection-7f3c")
          .param("limit", "1")
          .with(httpBasic("testuser", "testpass"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.count").value(1))
      .andExpect(jsonPath("$.rows[0].id").value(deadLetteredId))
      .andExpect(jsonPath("$.rows[0].failureReason").value("RETRY_EXHAUSTED"))
      .andExpect(jsonPath("$.rows[0].sourceId").value("10.0.0.1"))
      .andExpect(jsonPath("$.rows[0].accession").value("ACC-1"))
      .andExpect(jsonPath("$.rows[0].ageSeconds").exists())
      .andExpect(jsonPath("$.rows[0].lastError").value("java.net.UnknownHostException: oe"))
      .andExpect(jsonPath("$.rows[0].rawBytes").value(raw.length()))
      .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("^^^MTB"))));
  }

  @Test
  @DisplayName("stats separate what is still moving from what needs a person")
  void statsSeparateUndeliveredFromDeadLettered() throws Exception {
    mockMvc
      .perform(get("/admin/outbox/stats").with(httpBasic("testuser", "testpass")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.deadLettered").exists())
      .andExpect(jsonPath("$.byState.DELIVERED").exists())
      .andExpect(jsonPath("$.dispatcher.maxAttempts").exists());
  }

  @Test
  @DisplayName("an entry shows what OpenELIS said, attempt by attempt")
  void entryShowsItsAttemptHistory() throws Exception {
    mockMvc
      .perform(get("/admin/outbox/" + deliveredId).with(httpBasic("testuser", "testpass")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.state").value("DELIVERED"))
      .andExpect(jsonPath("$.oeReceipt").value("rcpt-1"))
      .andExpect(jsonPath("$.targetUri").value("https://oe:8443/api/OpenELIS-Global/analyzer/fhir"));

    mockMvc
      .perform(get("/admin/outbox/does-not-exist").with(httpBasic("testuser", "testpass")))
      .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("the held result itself is retrievable, in both forms")
  void payloadIsRetrievable() throws Exception {
    mockMvc
      .perform(get("/admin/outbox/" + deadLetteredId + "/payload").with(httpBasic("testuser", "testpass")))
      .andExpect(status().isOk())
      .andExpect(content().string(raw));

    mockMvc
      .perform(
        get("/admin/outbox/" + deadLetteredId + "/payload")
          .param("part", "fhir")
          .with(httpBasic("testuser", "testpass"))
      )
      .andExpect(status().isOk())
      .andExpect(content().string(org.hamcrest.Matchers.containsString("Bundle")));

    mockMvc
      .perform(
        get("/admin/outbox/" + deadLetteredId + "/payload")
          .param("part", "nonsense")
          .with(httpBasic("testuser", "testpass"))
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("an operator can send a held result again")
  void retryRequeuesAHeldResult() throws Exception {
    mockMvc
      .perform(post("/admin/outbox/" + deadLetteredId + "/retry").with(httpBasic("testuser", "testpass")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.state").value("PENDING"));

    org.junit.jupiter.api.Assertions.assertEquals(OutboxState.PENDING, store.get(deadLetteredId).orElseThrow().state());
    org.junit.jupiter.api.Assertions.assertEquals(
      "testuser",
      store.get(deadLetteredId).orElseThrow().retryRequestedBy()
    );
  }

  @Test
  @DisplayName("a delivered result is not sent again on request")
  void retryRefusesADeliveredResult() throws Exception {
    mockMvc
      .perform(post("/admin/outbox/" + deliveredId + "/retry").with(httpBasic("testuser", "testpass")))
      .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("everything an outage stopped can be released in one request")
  void bulkRetryReleasesEverythingDeadLettered() throws Exception {
    mockMvc
      .perform(
        post("/admin/outbox/retry")
          .contentType("application/json")
          .content("{\"ids\":[\"" + deadLetteredId + "\"]}")
          .with(httpBasic("testuser", "testpass"))
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.retried").value(1))
      .andExpect(jsonPath("$.requested").value(1))
      .andExpect(jsonPath("$.skipped").isEmpty());

    org.junit.jupiter.api.Assertions.assertEquals(OutboxState.PENDING, store.get(deadLetteredId).orElseThrow().state());
  }

  @Test
  @DisplayName("dismissing hides a dealt-with entry without destroying the evidence")
  void dismissHidesButKeeps() throws Exception {
    mockMvc
      .perform(post("/admin/outbox/" + deadLetteredId + "/dismiss").with(httpBasic("testuser", "testpass")))
      .andExpect(status().isOk());

    mockMvc
      .perform(
        get("/admin/outbox")
          .param("state", "DMQ")
          .param("connectionId", "bridge-connection-7f3c")
          .with(httpBasic("testuser", "testpass"))
      )
      .andExpect(jsonPath("$.rows[?(@.id == '" + deadLetteredId + "')]").isEmpty());
    mockMvc
      .perform(
        get("/admin/outbox")
          .param("state", "DMQ")
          .param("includeDismissed", "true")
          .with(httpBasic("testuser", "testpass"))
      )
      .andExpect(jsonPath("$.rows[?(@.id == '" + deadLetteredId + "')]").isNotEmpty());
    org.junit.jupiter.api.Assertions.assertTrue(store.rawPayload(deadLetteredId).isPresent());

    mockMvc
      .perform(post("/admin/outbox/" + deliveredId + "/dismiss").with(httpBasic("testuser", "testpass")))
      .andExpect(status().isConflict());
  }
}
