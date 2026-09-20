package org.itech.ahb.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.outbox.FailureReason;
import org.itech.ahb.outbox.OutboxAttempt;
import org.itech.ahb.outbox.OutboxDispatcher;
import org.itech.ahb.outbox.OutboxEntry;
import org.itech.ahb.outbox.OutboxProperties;
import org.itech.ahb.outbox.OutboxQuery;
import org.itech.ahb.outbox.OutboxState;
import org.itech.ahb.outbox.OutboxStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator view of the delivery outbox: what the bridge is holding, why, and the ability to send it
 * again once the cause is fixed.
 *
 * <p>Answers the question an operator actually asks, which is "where did that result go". Every
 * entry here is a real clinical result the bridge has and OpenELIS does not.
 *
 * <p>Payloads are served only from the dedicated payload endpoint, never from listings, and every
 * read of one is logged with who asked. Listing an entry tells an operator what they need for triage
 * without putting patient results into a response, a log or a screenshot.
 *
 * <p>Protected by the bridge's Spring Security config, which requires authentication for
 * {@code /admin/**}.
 */
@RestController
@RequestMapping("/admin/outbox")
@Slf4j
public class OutboxAdminController {

  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_BULK_IDS = 500;
  private static final int LIST_ERROR_MAX = 300;

  private final OutboxStore store;
  private final OutboxDispatcher dispatcher;
  private final OutboxProperties properties;

  public OutboxAdminController(OutboxStore store, OutboxDispatcher dispatcher, OutboxProperties properties) {
    this.store = store;
    this.dispatcher = dispatcher;
    this.properties = properties;
  }

  /** What the bridge is holding, filtered for triage. No payloads. */
  @GetMapping
  public ResponseEntity<Map<String, Object>> list(
    @RequestParam(required = false) String state,
    @RequestParam(required = false) String connectionId,
    @RequestParam(required = false) String failureReason,
    @RequestParam(required = false, defaultValue = "false") boolean includeDismissed,
    @RequestParam(required = false, defaultValue = "100") int limit,
    @RequestParam(required = false, defaultValue = "0") int offset
  ) {
    OutboxState parsedState;
    FailureReason parsedReason;
    try {
      parsedState = state == null || state.isBlank() ? null : OutboxState.valueOf(state.trim().toUpperCase());
      parsedReason = failureReason == null || failureReason.isBlank()
        ? null
        : FailureReason.valueOf(failureReason.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest()
        .body(Map.of("error", "invalid_filter", "message", e.getMessage() == null ? "unknown value" : e.getMessage()));
    }
    OutboxQuery query = new OutboxQuery(
      parsedState,
      connectionId,
      parsedReason,
      includeDismissed,
      limit <= 0 ? DEFAULT_LIMIT : limit,
      offset
    );
    Instant now = Instant.now();
    List<Map<String, Object>> rows = store.list(query).stream().map(entry -> summary(entry, now)).toList();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("limit", query.limit());
    body.put("offset", query.offset());
    body.put("count", rows.size());
    body.put("rows", rows);
    return ResponseEntity.ok(body);
  }

  /** The shape of the queue, for a dashboard or a health check. */
  @GetMapping("/stats")
  public ResponseEntity<Map<String, Object>> stats() {
    Map<OutboxState, Integer> counts = store.countsByState();
    Map<String, Object> byState = new LinkedHashMap<>();
    counts.forEach((state, count) -> byState.put(state.name(), count));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("byState", byState);
    body.put(
      "undelivered",
      counts.get(OutboxState.RECEIVED) + counts.get(OutboxState.PENDING) + counts.get(OutboxState.RETRYING)
    );
    body.put("deadLettered", counts.get(OutboxState.DMQ));
    body.put(
      "oldestUndeliveredAgeSeconds",
      store
        .oldestUndeliveredReceivedAt()
        .map(received -> Math.max(0, Instant.now().getEpochSecond() - received.getEpochSecond()))
        .orElse(null)
    );
    Map<String, Object> dispatcherState = new LinkedHashMap<>();
    dispatcherState.put("running", dispatcher.isRunning());
    dispatcherState.put("inFlightId", dispatcher.inFlightId());
    dispatcherState.put("lastPollAt", dispatcher.lastPollAt());
    dispatcherState.put("maxAttempts", properties.getRetry().getMaxAttempts());
    body.put("dispatcher", dispatcherState);
    return ResponseEntity.ok(body);
  }

  /** One entry with its attempt history, so an operator can see exactly what OpenELIS said and when. */
  @GetMapping("/{id}")
  public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
    return store
      .get(id)
      .map(entry -> {
        Map<String, Object> body = new LinkedHashMap<>(summary(entry, Instant.now()));
        body.put("rawHash", entry.rawHash());
        body.put("fhirHash", entry.fhirHash());
        body.put("targetUri", entry.targetUri());
        body.put("lastResponseExcerpt", entry.lastResponseExcerpt());
        body.put("lastError", entry.lastError());
        body.put("attemptHistory", store.attempts(id).stream().map(this::attempt).toList());
        return ResponseEntity.ok(body);
      })
      .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "not_found", "id", id)));
  }

  /**
   * The message itself, either as received or as it will be sent.
   *
   * <p>This is patient data. Access is logged with the authenticated user and the caller's address,
   * and a deployment can switch the endpoint off entirely where reading results on the bridge host
   * is not permitted.
   */
  @GetMapping("/{id}/payload")
  public ResponseEntity<String> payload(
    @PathVariable String id,
    @RequestParam(required = false, defaultValue = "raw") String part,
    HttpServletRequest request
  ) {
    if (!properties.isPayloadAccessEnabled()) {
      return ResponseEntity.status(404).body("Payload access is disabled on this bridge");
    }
    boolean fhir = "fhir".equalsIgnoreCase(part);
    if (!fhir && !"raw".equalsIgnoreCase(part)) {
      return ResponseEntity.badRequest().body("part must be 'raw' or 'fhir'");
    }
    return (fhir ? store.fhirPayload(id) : store.rawPayload(id)).map(payload -> {
        log.warn(
          "OUTBOX_AUDIT action=PAYLOAD_READ id={} part={} actor={} remote={} bytes={}",
          id,
          fhir ? "fhir" : "raw",
          actor(),
          request.getRemoteAddr(),
          payload.length()
        );
        return ResponseEntity.ok()
          .contentType(fhir ? MediaType.valueOf("application/fhir+json") : MediaType.TEXT_PLAIN)
          .body(payload);
      }).orElseGet(
        () -> ResponseEntity.status(404).body("No " + (fhir ? "rendered" : "received") + " payload for " + id)
      );
  }

  /** Send one held result again, now. */
  @PostMapping("/{id}/retry")
  public ResponseEntity<Map<String, Object>> retry(@PathVariable String id) {
    Instant now = Instant.now();
    RetryOutcome outcome = retryOne(id, now);
    if (outcome.status() != 200) {
      return ResponseEntity.status(outcome.status()).body(Map.of("error", outcome.reason(), "id", id));
    }
    dispatcher.signal();
    return ResponseEntity.ok(Map.of("id", id, "state", OutboxState.PENDING.name()));
  }

  /**
   * Send many held results again, the usual shape of recovering from an outage: fix the cause, then
   * release everything it stopped.
   */
  @PostMapping("/retry")
  public ResponseEntity<Map<String, Object>> retryMany(@RequestBody BulkRetryRequest request) {
    List<String> ids = new ArrayList<>();
    if (request != null && request.ids() != null && !request.ids().isEmpty()) {
      if (request.ids().size() > MAX_BULK_IDS) {
        return ResponseEntity.badRequest()
          .body(Map.of("error", "too_many_ids", "message", "at most " + MAX_BULK_IDS + " ids per request"));
      }
      ids.addAll(request.ids());
    } else if (request != null && request.all()) {
      FailureReason reason = null;
      if (request.failureReason() != null && !request.failureReason().isBlank()) {
        try {
          reason = FailureReason.valueOf(request.failureReason().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
          return ResponseEntity.badRequest().body(Map.of("error", "invalid_failure_reason"));
        }
      }
      store
        .list(new OutboxQuery(OutboxState.DMQ, request.connectionId(), reason, false, OutboxQuery.MAX_LIMIT, 0))
        .forEach(entry -> ids.add(entry.id()));
    } else {
      return ResponseEntity.badRequest()
        .body(Map.of("error", "nothing_requested", "message", "provide ids or all=true"));
    }

    Instant now = Instant.now();
    List<Map<String, Object>> skipped = new ArrayList<>();
    int retried = 0;
    for (String id : ids) {
      RetryOutcome outcome = retryOne(id, now);
      if (outcome.status() == 200) {
        retried++;
      } else {
        skipped.add(Map.of("id", id, "reason", outcome.reason()));
      }
    }
    dispatcher.signal();
    log.warn("OUTBOX_AUDIT action=BULK_RETRY requested={} retried={} actor={}", ids.size(), retried, actor());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("requested", ids.size());
    body.put("retried", retried);
    body.put("skipped", skipped);
    return ResponseEntity.ok(body);
  }

  /** Hide a dead-lettered entry that has been dealt with. It is never deleted. */
  @PostMapping("/{id}/dismiss")
  public ResponseEntity<Map<String, Object>> dismiss(@PathVariable String id) {
    OutboxEntry entry = store.get(id).orElse(null);
    if (entry == null) {
      return ResponseEntity.status(404).body(Map.of("error", "not_found", "id", id));
    }
    if (entry.state() != OutboxState.DMQ) {
      return ResponseEntity.status(409).body(
        Map.of("error", "not_dead_lettered", "id", id, "state", entry.state().name())
      );
    }
    Instant now = Instant.now();
    store.dismiss(id, actor(), now);
    log.warn("OUTBOX_AUDIT action=DISMISS id={} actor={}", id, actor());
    return ResponseEntity.ok(Map.of("id", id, "dismissedAt", now.toString()));
  }

  private RetryOutcome retryOne(String id, Instant now) {
    OutboxEntry entry = store.get(id).orElse(null);
    if (entry == null) {
      return new RetryOutcome(404, "not_found");
    }
    if (entry.state() == OutboxState.DELIVERED) {
      return new RetryOutcome(409, "already_delivered");
    }
    if (entry.leaseUntil() != null && entry.leaseUntil().isAfter(now)) {
      return new RetryOutcome(409, "delivery_in_progress");
    }
    store.requestRetry(id, actor(), now);
    store.recordAttempt(id, OutboxAttempt.Kind.MANUAL, actor(), now, now, "REQUEUED", null, null, null);
    log.warn("OUTBOX_AUDIT action=RETRY id={} actor={}", id, actor());
    return new RetryOutcome(200, null);
  }

  private Map<String, Object> summary(OutboxEntry entry, Instant now) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", entry.id());
    row.put("state", entry.state().name());
    row.put("sourceId", entry.sourceId());
    row.put("connectionId", entry.connectionId());
    row.put("analyzerId", entry.analyzerId());
    row.put("protocol", entry.protocol().name());
    row.put("transport", entry.transport().name());
    row.put("profileId", entry.profileId());
    row.put("profileRevision", entry.profileRevision());
    row.put("accession", entry.accession());
    row.put("attempts", entry.attempts());
    row.put("receivedAt", entry.receivedAt());
    row.put("ageSeconds", entry.ageSeconds(now));
    row.put("nextAttemptAt", entry.nextAttemptAt());
    row.put("lastAttemptAt", entry.lastAttemptAt());
    row.put("deliveredAt", entry.deliveredAt());
    row.put("dmqAt", entry.dmqAt());
    row.put("failureReason", entry.failureReason() == null ? null : entry.failureReason().name());
    row.put("lastHttpStatus", entry.lastHttpStatus());
    row.put("lastError", truncate(entry.lastError()));
    row.put("oeReceipt", entry.oeReceipt());
    row.put("rawBytes", entry.rawByteLength());
    row.put("fhirBytes", entry.fhirByteLength());
    row.put("dismissedAt", entry.dismissedAt());
    row.put("retryRequestedBy", entry.retryRequestedBy());
    return row;
  }

  private Map<String, Object> attempt(OutboxAttempt attempt) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("attemptNo", attempt.attemptNo());
    row.put("kind", attempt.kind().name());
    row.put("actor", attempt.actor());
    row.put("startedAt", attempt.startedAt());
    row.put("finishedAt", attempt.finishedAt());
    row.put("outcome", attempt.outcome());
    row.put("httpStatus", attempt.httpStatus());
    row.put("error", attempt.error());
    row.put("responseExcerpt", attempt.responseExcerpt());
    return row;
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= LIST_ERROR_MAX ? value : value.substring(0, LIST_ERROR_MAX);
  }

  private static String actor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null || authentication.getName() == null ? "anonymous" : authentication.getName();
  }

  /** Bulk retry request: either explicit ids, or everything dead-lettered matching a filter. */
  public record BulkRetryRequest(List<String> ids, boolean all, String failureReason, String connectionId) {}

  private record RetryOutcome(int status, String reason) {}
}
