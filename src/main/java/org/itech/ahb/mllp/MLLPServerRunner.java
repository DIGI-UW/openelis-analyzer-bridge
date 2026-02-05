package org.itech.ahb.mllp;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Wrapper class to run an MLLP server asynchronously.
 */
@Component
public class MLLPServerRunner {

    /**
     * Runs the given MLLP server asynchronously.
     *
     * @param server the MLLP server to run
     */
    @Async
    public void run(MLLPServer server) {
        server.start();
    }
}
