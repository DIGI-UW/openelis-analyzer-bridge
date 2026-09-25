package org.itech.ahb.outbox;

import java.util.Objects;

/** Immutable file bytes plus the receipt-time parser context, before any parsing or delivery. */
public record ReceivedFile(
  byte[] content,
  String sourcePath,
  String connectionId,
  String analyzerId,
  String profileId,
  int profileRevision,
  String contextJson,
  String interpretationHash
) {
  public ReceivedFile {
    content = Objects.requireNonNull(content, "content").clone();
    if (content.length == 0) throw new IllegalArgumentException("cannot persist an empty file");
    Objects.requireNonNull(sourcePath, "sourcePath");
    Objects.requireNonNull(connectionId, "connectionId");
    Objects.requireNonNull(analyzerId, "analyzerId");
    Objects.requireNonNull(profileId, "profileId");
    Objects.requireNonNull(contextJson, "contextJson");
    Objects.requireNonNull(interpretationHash, "interpretationHash");
  }
  @Override
  public byte[] content() {
    return content.clone();
  }
}
