package org.itech.ahb.routing;

import org.itech.ahb.normalizer.MessageEnvelope;

/**
 * Routes messages from transport listeners to appropriate handlers.
 * <p>
 * This is a lightweight interface preparing for M7 normalizer implementation.
 * Current implementation directly forwards to HTTP, but future implementations
 * will add protocol normalization, transformation, and multi-destination routing.
 * </p>
 * <p>
 * Part of the Universal Analyzer Bridge architecture, this interface decouples
 * transport listeners (MLLP, Serial, File, HTTP) from message destinations,
 * enabling future enhancements like retry queues, message persistence, and
 * protocol transformation.
 * </p>
 *
 * @see org.itech.ahb.normalizer.MessageEnvelope
 * @see org.itech.ahb.routing.HttpForwardingRouter
 */
public interface MessageRouter {

    /**
     * Routes a message envelope to appropriate destination(s).
     * <p>
     * The envelope contains the raw message along with metadata about how it
     * was received (protocol, transport, source identifier, analyzer ID).
     * </p>
     *
     * @param envelope the message with transport metadata
     * @return true if routing succeeded, false otherwise
     */
    boolean route(MessageEnvelope envelope);
}
