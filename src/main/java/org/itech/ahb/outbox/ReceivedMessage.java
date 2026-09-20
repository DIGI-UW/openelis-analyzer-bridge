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
  Instant receivedAt
) {}
