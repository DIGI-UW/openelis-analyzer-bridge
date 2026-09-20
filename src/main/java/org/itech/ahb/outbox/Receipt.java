package org.itech.ahb.outbox;

/**
 * Result of persisting a received message.
 *
 * @param id the RECEIVED row's identifier, used to attach rendered deliveries or a dead-letter
 * @param rawHash SHA-256 of the received content, the delivery identity's content component
 * @param alreadyPresent true when identical content from the same source was already stored, i.e.
 *     the analyzer retransmitted; the prior row is kept and nothing is duplicated
 */
public record Receipt(String id, String rawHash, boolean alreadyPresent) {}
