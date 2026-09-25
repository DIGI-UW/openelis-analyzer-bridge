package org.itech.ahb.lib.astm.communication;

/** Receiver-owned persistence boundary. A frame callback must commit before returning to the ACK writer. */
public interface AstmReceiptObserver {
  AstmReceiptObserver NONE = new AstmReceiptObserver() {};

  default void frame(byte[] exactFrame) {}

  /** Called only after EOT and an ETX-terminated message. Must durably hand off before returning. */
  default void complete(String message) {}

  /** Previously accepted frames must remain available when receipt is interrupted. */
  default void interrupted() {}
}
