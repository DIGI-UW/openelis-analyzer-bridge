package org.itech.ahb.mllp;

import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.hl7v2.app.HL7Service;
import ca.uhn.hl7v2.app.SimpleServer;
import ca.uhn.hl7v2.llp.LowerLayerProtocol;
import ca.uhn.hl7v2.llp.MinLowerLayerProtocol;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.protocol.ReceivingApplication;
import ca.uhn.hl7v2.protocol.ReceivingApplicationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Research test to investigate what metadata HAPI SimpleServer provides.
 * <p>
 * This test captures the metadata Map passed to ReceivingApplication.processMessage()
 * to determine how to extract source IP and other connection information.
 * </p>
 */
@DisplayName("HAPI Metadata Research")
class HapiMetadataResearchTest {

    private static final int TEST_PORT = 12576;
    private static final byte VT = 0x0B;
    private static final byte FS = 0x1C;
    private static final byte CR = 0x0D;

    private SimpleServer server;
    private Thread serverThread;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Test
    @DisplayName("Should capture and log all metadata keys from HAPI")
    @Timeout(15)
    void investigateHapiMetadata() throws Exception {
        // Capture metadata for investigation
        AtomicReference<Map<String, Object>> capturedMetadata = new AtomicReference<>();
        CountDownLatch messageLatch = new CountDownLatch(1);

        // Create HAPI SimpleServer
        server = new SimpleServer(TEST_PORT, false);

        // Register application that captures metadata
        server.registerApplication("*", "*", new ReceivingApplication<Message>() {
            @Override
            public Message processMessage(Message message, Map<String, Object> metadata)
                    throws ReceivingApplicationException {
                System.out.println("\n=== HAPI Metadata Investigation ===");
                System.out.println("Metadata map size: " + metadata.size());
                System.out.println("Metadata keys: " + metadata.keySet());
                System.out.println("\nAll metadata entries:");
                metadata.forEach((key, value) -> {
                    System.out.println("  " + key + " = " + value + " (" +
                        (value != null ? value.getClass().getSimpleName() : "null") + ")");
                });
                System.out.println("=== End Metadata ===\n");

                capturedMetadata.set(metadata);
                messageLatch.countDown();

                try {
                    return message.generateACK();
                } catch (Exception e) {
                    throw new ReceivingApplicationException(e);
                }
            }

            @Override
            public boolean canProcess(Message message) {
                return true;
            }
        });

        // Start server in background
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        serverThread.start();

        // Give server time to start
        Thread.sleep(500);

        // Send test HL7 message with MLLP framing
        String hl7Message = "MSH|^~\\&|TestApp|TestFac|DestApp|DestFac|20260205120000||ORM^O01|12345|P|2.5.1\r" +
                           "PID|1||12345^^^MRN||Doe^John||19800101|M\r";

        sendMLLPMessage("localhost", TEST_PORT, hl7Message);

        // Wait for message processing
        assertTrue(messageLatch.await(10, TimeUnit.SECONDS), "Should receive message");

        // Verify metadata was captured
        Map<String, Object> metadata = capturedMetadata.get();
        assertNotNull(metadata, "Metadata should not be null");

        // Print results for investigation
        System.out.println("\n=== Metadata Capture Results ===");
        System.out.println("Total metadata entries: " + metadata.size());

        // Check for common source IP keys
        String[] possibleIpKeys = {
            "SENDING_IP", "REMOTE_ADDRESS", "SOURCE_IP", "REMOTE_ADDR",
            "CLIENT_IP", "REMOTE_HOST", "PEER_ADDRESS", "CONNECTION_ADDRESS"
        };

        boolean foundIp = false;
        for (String key : possibleIpKeys) {
            if (metadata.containsKey(key)) {
                System.out.println("FOUND IP KEY: " + key + " = " + metadata.get(key));
                foundIp = true;
            }
        }

        if (!foundIp) {
            System.out.println("WARNING: No standard IP key found. All keys: " + metadata.keySet());
            System.out.println("Will need to implement fallback strategy (ThreadLocal or ConnectionListener)");
        }

        // Document findings
        System.out.println("\n=== Implementation Guidance ===");
        if (foundIp) {
            System.out.println("✅ Can extract source IP from metadata");
        } else {
            System.out.println("⚠️ Need fallback: ThreadLocal + custom ConnectionListener");
        }
        System.out.println("====================================\n");
    }

    /**
     * Sends an MLLP-framed HL7 message to the specified host and port.
     */
    private String sendMLLPMessage(String host, int port, String message) throws IOException {
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Send MLLP frame: VT + message + FS + CR
            out.write(VT);
            out.write(message.getBytes(StandardCharsets.UTF_8));
            out.write(FS);
            out.write(CR);
            out.flush();

            // Read MLLP response
            return readMLLPResponse(in);
        }
    }

    /**
     * Reads an MLLP-framed response from the input stream.
     */
    private String readMLLPResponse(InputStream in) throws IOException {
        // Read start block
        int startByte = in.read();
        if (startByte != VT) {
            throw new IOException("Expected VT start block, got: 0x" + Integer.toHexString(startByte));
        }

        // Read until FS + CR
        StringBuilder response = new StringBuilder();
        int prevByte = -1;
        int currentByte;
        while ((currentByte = in.read()) != -1) {
            if (prevByte == FS && currentByte == CR) {
                // Remove trailing FS
                if (response.length() > 0) {
                    response.setLength(response.length() - 1);
                }
                break;
            }
            response.append((char) currentByte);
            prevByte = currentByte;
        }

        return response.toString();
    }
}
