package org.itech.ahb.mllp;

import ca.uhn.hl7v2.app.SimpleServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.routing.MessageRouter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Spring-managed HAPI MLLP listener for receiving HL7 v2.x messages.
 * <p>
 * Replaces the custom MLLPServer/MLLPServerRunner/MLLPServerTrigger with
 * HAPI's production-grade {@link SimpleServer}, aligning with the
 * Universal Bridge architecture (M2a).
 * </p>
 * <p>
 * Message flow:
 * <pre>
 * Analyzer → HAPI SimpleServer (MLLP framing)
 *   → RateLimitingReceivingApplication (security)
 *     → HapiReceivingApplication (routing)
 *       → MessageEnvelope → MessageRouter → HTTP endpoint
 * </pre>
 * </p>
 * <p>
 * HAPI SimpleServer provides:
 * <ul>
 *   <li>RFC 3863 compliant MLLP framing (VT/FS/CR delimiters)</li>
 *   <li>Properly-formed HL7 ACK/NAK responses with all required MSH fields</li>
 *   <li>Safe MSH segment parsing</li>
 *   <li>Built-in connection and thread management</li>
 * </ul>
 * </p>
 *
 * @see HapiReceivingApplication
 * @see RateLimitingReceivingApplication
 * @see MessageRouter
 */
@Component
@ConditionalOnProperty(name = "org.itech.ahb.mllp.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(MLLPConfig.class)
@Slf4j
public class HapiMLLPListener {

    private final MLLPConfig config;
    private final MessageRouter router;
    private SimpleServer server;
    private ExecutorService executorService;
    private RateLimitingReceivingApplication rateLimiter;

    /**
     * Constructs a new HapiMLLPListener.
     *
     * @param config the MLLP configuration properties
     * @param router the message router for forwarding messages
     */
    public HapiMLLPListener(MLLPConfig config, MessageRouter router) {
        this.config = config;
        this.router = router;
    }

    /**
     * Starts the HAPI MLLP server after Spring context initialization.
     * <p>
     * Creates the HAPI SimpleServer with the receiving application chain:
     * RateLimitingReceivingApplication → HapiReceivingApplication → MessageRouter
     * </p>
     */
    @PostConstruct
    public void start() {
        log.info("Starting HAPI MLLP listener on port {} (timeout={}ms)",
            config.getPort(), config.getTimeout());

        try {
            // Create HAPI SimpleServer
            server = new SimpleServer(config.getPort(), false);

            // Create receiving application chain
            HapiReceivingApplication receivingApp = new HapiReceivingApplication(router);
            rateLimiter = new RateLimitingReceivingApplication(receivingApp);

            // Register for all HL7 message types
            server.registerApplication("*", "*", rateLimiter);

            // Start server asynchronously (HAPI's start() blocks)
            executorService = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r);
                t.setName("hapi-mllp-server");
                t.setDaemon(false);
                return t;
            });

            executorService.submit(() -> {
                try {
                    server.start();
                } catch (Exception e) {
                    log.error("HAPI MLLP server failed on port {}", config.getPort(), e);
                }
            });

            log.info("HAPI MLLP listener started on port {}", config.getPort());

        } catch (Exception e) {
            log.error("Failed to initialize HAPI MLLP listener on port {}", config.getPort(), e);
            throw new RuntimeException("MLLP listener initialization failed", e);
        }
    }

    /**
     * Stops the HAPI MLLP server during Spring context shutdown.
     */
    @PreDestroy
    public void stop() {
        log.info("Stopping HAPI MLLP listener on port {}", config.getPort());

        if (server != null) {
            server.stop();
        }

        if (rateLimiter != null) {
            rateLimiter.shutdown();
        }

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("MLLP executor did not terminate within 30s, forcing shutdown");
                    executorService.shutdownNow();
                    if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                        log.error("MLLP executor did not terminate after forced shutdown");
                    }
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for MLLP executor shutdown");
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("HAPI MLLP listener stopped");
    }

    /**
     * Checks if the MLLP server is currently running.
     *
     * @return true if the server is running
     */
    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    /**
     * Gets the port this listener is configured on.
     *
     * @return the listen port
     */
    public int getPort() {
        return config.getPort();
    }
}
