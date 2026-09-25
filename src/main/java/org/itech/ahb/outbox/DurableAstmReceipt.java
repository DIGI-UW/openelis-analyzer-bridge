package org.itech.ahb.outbox;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import org.itech.ahb.lib.astm.communication.AstmReceiptObserver;

/** One transport session: exact acknowledged frames held until atomic complete-message handoff. */
public final class DurableAstmReceipt implements AstmReceiptObserver {

  private final OutboxStore store;
  private final ReceivedMessage source;
  private final String sessionId = "astm-session:" + UUID.randomUUID();
  private final ByteArrayOutputStream frames = new ByteArrayOutputStream();

  public DurableAstmReceipt(OutboxStore store, ReceivedMessage source) {
    this.store = store;
    this.source = source;
  }

  @Override
  public void frame(byte[] exactFrame) {
    ByteArrayOutputStream candidate = new ByteArrayOutputStream();
    candidate.writeBytes(frames.toByteArray());
    candidate.writeBytes(exactFrame);
    store.receiveAstmFrames(sessionId, source, candidate.toByteArray());
    frames.writeBytes(exactFrame);
  }

  @Override
  public void complete(String message) {
    store.completeAstmSession(
      sessionId,
      new ReceivedMessage(
        source.sourceId(),
        source.sourcePort(),
        source.protocol(),
        source.transport(),
        source.transport() == org.itech.ahb.model.Transport.TCP
          ? org.itech.ahb.normalizer.ASTMBridgeAdapter.extractSenderFromHRecord(message)
          : source.protocolHint(),
        message,
        null,
        source.receivedAt(),
        source.listenerPort()
      ),
      org.itech.ahb.normalizer.MessageNormalizer.isQueryOnlyAstmMessage(message)
    );
  }
}
