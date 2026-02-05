package org.itech.ahb.mllp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * TCP server implementing MLLP (Minimal Lower Layer Protocol) framing for HL7 messages.
 * <p>
 * MLLP framing uses:
 * <ul>
 *   <li>VT (0x0B) as message start delimiter</li>
 *   <li>FS (0x1C) followed by CR (0x0D) as message end delimiter</li>
 * </ul>
 * </p>
 * <p>
 * The server listens for incoming connections, extracts HL7 messages from the MLLP frame,
 * and forwards them to the configured handler for processing. ACK/NAK responses are sent
 * back to the client wrapped in MLLP framing.
 * </p>
 */
@Slf4j
public class MLLPServer {

    /** MLLP Start Block character - VT (Vertical Tab) */
    public static final byte VT = 0x0B;

    /** MLLP End Block character - FS (File Separator) */
    public static final byte FS = 0x1C;

    /** MLLP Carriage Return */
    public static final byte CR = 0x0D;

    private final int port;
    private final int timeout;
    private final int maxMessageSize;
    private final MLLPHandler handler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executorService;
    private volatile ServerSocket serverSocket;

    /**
     * Constructs a new MLLPServer with the specified configuration and handler.
     *
     * @param config the MLLP configuration
     * @param handler the handler for processing HL7 messages
     */
    public MLLPServer(MLLPConfig config, MLLPHandler handler) {
        this.port = config.getPort();
        this.timeout = config.getTimeout();
        this.maxMessageSize = config.getMaxMessageSize();
        this.handler = handler;
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("mllp-connection-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the MLLP server listening on the configured port.
     * This method blocks until the server is stopped.
     */
    public void start() {
        if (running.getAndSet(true)) {
            log.warn("MLLP server is already running on port {}", port);
            return;
        }

        try {
            serverSocket = new ServerSocket(port);
            log.info("MLLP server started on port {}", port);

            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(timeout);
                    executorService.submit(() -> handleConnection(clientSocket));
                } catch (IOException e) {
                    if (running.get()) {
                        log.error("Error accepting MLLP connection on port {}", port, e);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to start MLLP server on port {}", port, e);
        } finally {
            running.set(false);
            closeServerSocket();
        }
    }

    /**
     * Stops the MLLP server.
     */
    public void stop() {
        log.info("Stopping MLLP server on port {}", port);
        running.set(false);
        closeServerSocket();
        executorService.shutdown();
    }

    /**
     * Checks if the server is currently running.
     *
     * @return true if the server is running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Gets the port this server is listening on.
     *
     * @return the listen port
     */
    public int getPort() {
        return port;
    }

    /**
     * Handles a single client connection.
     *
     * @param socket the client socket
     */
    private void handleConnection(Socket socket) {
        String sourceIp = extractSourceIp(socket);
        log.debug("Accepted MLLP connection from {}", sourceIp);

        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            while (running.get() && !socket.isClosed()) {
                String hl7Message = readMLLPMessage(in);
                if (hl7Message == null) {
                    break; // Connection closed or read error
                }

                log.debug("Received HL7 message from {}: {} bytes", sourceIp, hl7Message.length());
                log.trace("HL7 message content: {}", hl7Message);

                String response = handler.handleMessage(hl7Message, sourceIp);
                sendMLLPResponse(out, response);
            }
        } catch (IOException e) {
            if (running.get()) {
                log.warn("Connection error from {}: {}", sourceIp, e.getMessage());
            }
        } finally {
            closeSocket(socket);
            log.debug("Closed MLLP connection from {}", sourceIp);
        }
    }

    /**
     * Reads an MLLP-framed message from the input stream.
     * <p>
     * Expects: VT + message content + FS + CR
     * </p>
     *
     * @param in the input stream
     * @return the HL7 message (without MLLP framing), or null if connection closed
     * @throws IOException if a read error occurs
     */
    private String readMLLPMessage(InputStream in) throws IOException {
        // Wait for start block (VT)
        int startByte = in.read();
        if (startByte == -1) {
            return null; // Connection closed
        }

        if (startByte != VT) {
            log.warn("Expected MLLP start block (VT/0x0B), got: 0x{}", Integer.toHexString(startByte));
            // Try to recover by reading until we find VT or connection closes
            while (startByte != VT && startByte != -1) {
                startByte = in.read();
            }
            if (startByte == -1) {
                return null;
            }
        }

        // Read message until FS + CR
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int prevByte = -1;
        int currentByte;

        while ((currentByte = in.read()) != -1) {
            if (buffer.size() > maxMessageSize) {
                throw new IOException("MLLP message exceeds maximum size of " + maxMessageSize + " bytes");
            }

            // Check for end of message: FS followed by CR
            if (prevByte == FS && currentByte == CR) {
                // Remove the FS that was added to buffer
                byte[] data = buffer.toByteArray();
                return new String(data, 0, data.length - 1, StandardCharsets.UTF_8);
            }

            buffer.write(currentByte);
            prevByte = currentByte;
        }

        // Connection closed before message completed
        if (buffer.size() > 0) {
            log.warn("Connection closed before MLLP message completed, {} bytes received", buffer.size());
        }
        return null;
    }

    /**
     * Sends an MLLP-framed response.
     *
     * @param out the output stream
     * @param response the response message (typically HL7 ACK/NAK)
     * @throws IOException if a write error occurs
     */
    private void sendMLLPResponse(OutputStream out, String response) throws IOException {
        byte[] messageBytes = response.getBytes(StandardCharsets.UTF_8);

        // Write MLLP frame: VT + message + FS + CR
        out.write(VT);
        out.write(messageBytes);
        out.write(FS);
        out.write(CR);
        out.flush();

        log.debug("Sent MLLP response: {} bytes", messageBytes.length);
        log.trace("Response content: {}", response);
    }

    /**
     * Extracts the source IP address from a socket connection.
     *
     * @param socket the client socket
     * @return the source IP address as a string
     */
    private String extractSourceIp(Socket socket) {
        InetSocketAddress remoteAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private void closeServerSocket() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log.error("Error closing MLLP server socket", e);
            }
        }
    }

    private void closeSocket(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                log.debug("Error closing client socket", e);
            }
        }
    }
}
