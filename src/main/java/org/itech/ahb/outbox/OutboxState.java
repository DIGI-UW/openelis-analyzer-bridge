package org.itech.ahb.outbox;

/**
 * Lifecycle of one received result on its way to OpenELIS.
 *
 * <pre>
 * RECEIVED -> PENDING -> RETRYING -> DELIVERED
 *                  \        /
 *                   \      v
 *                    -> DMQ
 * </pre>
 *
 * <p>RECEIVED and DMQ are both reachable directly from receipt: a message whose source cannot be
 * identified, or which cannot be rendered into the OpenELIS contract, is dead-lettered with its full
 * payload rather than dropped.
 */
public enum OutboxState {
  /** Persisted on receipt, before the source is identified or the bundle is rendered. */
  RECEIVED,
  /** Rendered and waiting for its first delivery attempt. */
  PENDING,
  /** At least one attempt failed in a way that can succeed later. */
  RETRYING,
  /** OpenELIS durably accepted it. Terminal. */
  DELIVERED,
  /** Cannot be delivered without a human: retries exhausted, rejected, or unrenderable. Terminal until an operator retries. */
  DMQ;

  public boolean isTerminal() {
    return this == DELIVERED || this == DMQ;
  }

  /** States the dispatcher may claim work from. */
  public boolean isDispatchable() {
    return this == RECEIVED || this == PENDING || this == RETRYING;
  }
}
