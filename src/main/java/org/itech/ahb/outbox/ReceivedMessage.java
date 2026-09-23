package org.itech.ahb.outbox;

import java.time.Instant;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;

/**
 * A message as it arrived, before the source is identified or anything is parsed.
 *
 * <p>This is what the outbox persists first, so that a message is recoverable even when identity
 * resolution or rendering is what fails.
 */
public record ReceivedMessage(
  String sourceId,
  Integer sourcePort,
  Protocol protocol,
  Transport transport,
  String protocolHint,
  String rawText,
  String rawCharset,
  Instant receivedAt,
  Integer listenerPort
) {
  /** A message that did not arrive on a shared network listener. */
  public ReceivedMessage(
    String sourceId,
    Integer sourcePort,
    Protocol protocol,
    Transport transport,
    String protocolHint,
    String rawText,
    String rawCharset,
    Instant receivedAt
  ) {
    this(sourceId, sourcePort, protocol, transport, protocolHint, rawText, rawCharset, receivedAt, null);
  }
}
