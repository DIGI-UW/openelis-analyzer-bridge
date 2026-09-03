package org.itech.ahb.serial;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.model.Protocol;

/**
 * Buffer for accumulating serial port data and detecting complete messages.
 * <p>
 * Supports two framing protocols:
 * <ul>
 *   <li><b>ASTM LIS2-A2</b>: Uses ENQ/ACK handshake, STX/ETX/ETB framing with checksums</li>
 *   <li><b>HL7 MLLP</b>: Uses VT (0x0B) start block, FS+CR (0x1C 0x0D) end block</li>
 * </ul>
 * </p>
 */
@Slf4j
public class SerialFrameBuffer {

    // ASTM LIS2-A2 Control Characters
    public static final byte ENQ = 0x05;  // Enquiry - Start transmission
    public static final byte ACK = 0x06;  // Acknowledge
    public static final byte NAK = 0x15;  // Negative Acknowledge
    public static final byte EOT = 0x04;  // End of Transmission
    public static final byte STX = 0x02;  // Start of Text (frame start)
    public static final byte ETX = 0x03;  // End of Text (final frame)
    public static final byte ETB = 0x17;  // End of Text Block (intermediate frame)
    public static final byte CR = 0x0D;   // Carriage Return
    public static final byte LF = 0x0A;   // Line Feed

    // HL7 MLLP Control Characters
    public static final byte VT = 0x0B;   // Vertical Tab - Start block
    public static final byte FS = 0x1C;   // File Separator - End block (followed by CR)

    // Buffer configuration
    private static final int INITIAL_CAPACITY = 8192;
    private static final int MAX_CAPACITY = 1024 * 1024;  // 1MB max message size

    private ByteBuffer buffer;
    private final Protocol protocol;
    private Instant lastDataTime;
    private final List<String> completedMessages;
    private final List<byte[]> pendingResponses;

    // ASTM state machine
    private ASTMState astmState;
    private final StringBuilder currentASTMMessage;
    private int expectedFrameNumber;

    /**
     * ASTM protocol state machine states.
     */
    public enum ASTMState {
        IDLE,              // Waiting for ENQ
        ESTABLISHED,       // Received ENQ, sent ACK, waiting for frames
        RECEIVING_FRAME,   // Currently receiving a frame
        FRAME_COMPLETE,    // Frame received, waiting for next frame or EOT
        TERMINATED         // Received EOT, message complete
    }

    /**
     * Creates a new SerialFrameBuffer with the specified protocol mode.
     *
     * @param protocol the protocol declared by the pinned analyzer profile
     */
    public SerialFrameBuffer(Protocol protocol) {
        if (protocol != Protocol.ASTM && protocol != Protocol.HL7) {
            throw new IllegalArgumentException("Serial framing requires ASTM or HL7");
        }
        this.protocol = protocol;
        this.buffer = ByteBuffer.allocate(INITIAL_CAPACITY);
        this.completedMessages = new ArrayList<>();
        this.pendingResponses = new ArrayList<>();
        this.currentASTMMessage = new StringBuilder();
        this.astmState = ASTMState.IDLE;
        this.expectedFrameNumber = 1;
        this.lastDataTime = Instant.now();
    }

    /**
     * Gets the current ASTM state machine state.
     */
    public ASTMState getAstmState() {
        return astmState;
    }

    /**
     * Appends incoming data to the buffer and processes it.
     * Thread-safe: synchronized to handle concurrent access from serial port callbacks and timeout checkers.
     *
     * @param data the incoming data bytes
     * @return list of response bytes to send back (ACK, NAK, etc.)
     */
    public synchronized List<byte[]> appendData(byte[] data) {
        if (data == null || data.length == 0) {
            return pendingResponses;
        }

        lastDataTime = Instant.now();
        pendingResponses.clear();

        // Ensure buffer capacity
        ensureCapacity(data.length);

        // Process each byte
        for (byte b : data) {
            processInputByte(b);
        }

        return new ArrayList<>(pendingResponses);
    }

    /**
     * Processes a single input byte according to the current protocol state.
     */
    private void processInputByte(byte b) {
        if (protocol == Protocol.HL7) {
            processHL7Byte(b);
        } else {
            processASTMByte(b);
        }
    }

    /**
     * Processes a byte for ASTM LIS2-A2 protocol.
     */
    private void processASTMByte(byte b) {
        switch (astmState) {
            case IDLE:
                if (b == ENQ) {
                    log.debug("ASTM: Received ENQ, sending ACK");
                    pendingResponses.add(new byte[]{ACK});
                    astmState = ASTMState.ESTABLISHED;
                    currentASTMMessage.setLength(0);
                    expectedFrameNumber = 1;
                } else if (b == STX) {
                    // Non-compliant sender - start receiving without ENQ
                    log.debug("ASTM: Received STX without ENQ (non-compliant mode)");
                    astmState = ASTMState.RECEIVING_FRAME;
                    buffer.clear();
                }
                break;

            case ESTABLISHED:
                if (b == STX) {
                    astmState = ASTMState.RECEIVING_FRAME;
                    buffer.clear();
                } else if (b == EOT) {
                    log.debug("ASTM: Received EOT, message complete");
                    completeASTMMessage();
                    astmState = ASTMState.IDLE;
                }
                break;

            case RECEIVING_FRAME:
                buffer.put(b);
                if (b == ETX || b == ETB) {
                    // Need to read checksum and CR LF
                    astmState = ASTMState.FRAME_COMPLETE;
                }
                break;

            case FRAME_COMPLETE:
                buffer.put(b);
                // Check if we have checksum + CR + LF
                if (buffer.position() >= 3) {
                    int pos = buffer.position();
                    byte[] data = buffer.array();
                    if (pos >= 2 && data[pos - 2] == CR && data[pos - 1] == LF) {
                        // Frame is complete
                        if (validateAndExtractFrame()) {
                            log.debug("ASTM: Frame {} validated, sending ACK", expectedFrameNumber);
                            pendingResponses.add(new byte[]{ACK});
                            expectedFrameNumber = (expectedFrameNumber % 8) + 1;
                        } else {
                            log.warn("ASTM: Frame validation failed, sending NAK");
                            pendingResponses.add(new byte[]{NAK});
                        }
                        buffer.clear();
                        astmState = ASTMState.ESTABLISHED;
                    }
                }
                break;

            case TERMINATED:
                // Reset state
                astmState = ASTMState.IDLE;
                break;
        }
    }

    /**
     * Validates the current frame and extracts the text content.
     *
     * @return true if the frame is valid
     */
    private boolean validateAndExtractFrame() {
        buffer.flip();
        byte[] frameData = new byte[buffer.remaining()];
        buffer.get(frameData);

        if (frameData.length < 5) {
            log.warn("ASTM: Frame too short: {} bytes", frameData.length);
            return false;
        }

        // Frame format: <FN><text><ETX/ETB><C1><C2><CR><LF>
        int frameNumber = Character.getNumericValue((char) frameData[0]);

        // Find ETX or ETB position
        int textEnd = -1;
        byte terminator = 0;
        for (int i = 1; i < frameData.length - 4; i++) {
            if (frameData[i] == ETX || frameData[i] == ETB) {
                textEnd = i;
                terminator = frameData[i];
                break;
            }
        }

        if (textEnd == -1) {
            log.warn("ASTM: No frame terminator found");
            return false;
        }

        // Extract text (ASTM uses ISO-8859-1 encoding)
        String text = new String(frameData, 1, textEnd - 1, StandardCharsets.ISO_8859_1);

        // Verify checksum
        String receivedChecksum = new String(frameData, textEnd + 1, 2, StandardCharsets.ISO_8859_1);
        String calculatedChecksum = calculateChecksum(frameNumber, text, terminator);

        if (!receivedChecksum.equalsIgnoreCase(calculatedChecksum)) {
            log.warn("ASTM: Checksum mismatch. Received: {}, Calculated: {}",
                receivedChecksum, calculatedChecksum);
            return false;
        }

        // Append text to message
        currentASTMMessage.append(text);

        if (terminator == ETX) {
            log.debug("ASTM: Final frame received");
        }

        return true;
    }

    /**
     * Calculates the ASTM checksum for a frame.
     */
    private String calculateChecksum(int frameNumber, String text, byte terminator) {
        int checksum = 0;
        checksum += (byte) Character.forDigit(frameNumber, 10);
        // Use ISO-8859-1 for ASTM protocol consistency
        for (byte b : text.getBytes(StandardCharsets.ISO_8859_1)) {
            checksum += b;
        }
        checksum += terminator;
        checksum %= 256;
        return String.format("%02X", checksum);
    }

    /**
     * Completes the current ASTM message and adds it to the completed list.
     */
    private void completeASTMMessage() {
        if (currentASTMMessage.length() > 0) {
            String message = currentASTMMessage.toString();
            completedMessages.add(message);
            log.info("ASTM: Complete message received ({} bytes)", message.length());
            currentASTMMessage.setLength(0);
        }
    }

    /**
     * Processes a byte for HL7 MLLP protocol.
     */
    private void processHL7Byte(byte b) {
        if (b == VT) {
            // Start of new message
            buffer.clear();
            log.debug("HL7: Received VT (start of message)");
        } else if (b == FS) {
            // End of message (FS will be followed by CR)
            // Mark that we're at end, wait for CR
            buffer.put(b);
        } else if (b == CR && buffer.position() > 0) {
            // Check if previous byte was FS
            byte[] data = buffer.array();
            int pos = buffer.position();
            if (pos > 0 && data[pos - 1] == FS) {
                // Complete HL7 message
                buffer.flip();
                byte[] messageData = new byte[buffer.remaining() - 1]; // Exclude FS
                buffer.get(messageData);
                // HL7 typically uses UTF-8 encoding
                String message = new String(messageData, StandardCharsets.UTF_8);
                completedMessages.add(message);
                log.info("HL7: Complete message received ({} bytes)", message.length());
                buffer.clear();

                // Send ACK for HL7
                String ack = generateHL7ACK(message);
                pendingResponses.add(wrapMLLP(ack.getBytes(StandardCharsets.UTF_8)));
            } else {
                buffer.put(b);
            }
        } else {
            buffer.put(b);
        }
    }

    /**
     * Generates an HL7 ACK message.
     */
    private String generateHL7ACK(String originalMessage) {
        // Extract message control ID from MSH-10
        String messageControlId = "UNKNOWN";
        try {
            String[] segments = originalMessage.split("\r");
            if (segments.length > 0 && segments[0].startsWith("MSH|")) {
                String[] fields = segments[0].split("\\|");
                if (fields.length > 9 && fields[9] != null && !fields[9].isEmpty()) {
                    messageControlId = fields[9];
                } else {
                    log.warn("HL7 message missing control ID (MSH-10), using 'UNKNOWN' in ACK");
                }
            } else {
                log.warn("Invalid HL7 message format (no MSH segment), using 'UNKNOWN' in ACK");
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            log.warn("Error parsing HL7 MSH segment for control ID: {}", e.getMessage());
        } catch (NullPointerException e) {
            log.warn("Null field in HL7 MSH segment: {}", e.getMessage());
        }

        return String.format(
            "MSH|^~\\&|BRIDGE|BRIDGE|ANALYZER|ANALYZER|%s||ACK|%s|P|2.5.1\r" +
            "MSA|AA|%s\r",
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(java.time.LocalDateTime.now()),
            messageControlId,
            messageControlId
        );
    }

    /**
     * Wraps a message in MLLP framing (VT + message + FS + CR).
     */
    private byte[] wrapMLLP(byte[] message) {
        byte[] wrapped = new byte[message.length + 3];
        wrapped[0] = VT;
        System.arraycopy(message, 0, wrapped, 1, message.length);
        wrapped[message.length + 1] = FS;
        wrapped[message.length + 2] = CR;
        return wrapped;
    }

    /**
     * Gets and clears the list of completed messages.
     * Thread-safe: synchronized to prevent concurrent access.
     *
     * @return list of complete messages
     */
    public synchronized List<String> getCompletedMessages() {
        List<String> messages = new ArrayList<>(completedMessages);
        completedMessages.clear();
        return messages;
    }

    /**
     * Checks if there are any completed messages waiting.
     * Thread-safe: synchronized for consistency.
     */
    public synchronized boolean hasCompletedMessages() {
        return !completedMessages.isEmpty();
    }

    /**
     * Clears the buffer and resets state.
     * Thread-safe: synchronized to prevent concurrent modification.
     * Call this on timeout or error.
     */
    public synchronized void reset() {
        buffer.clear();
        completedMessages.clear();
        pendingResponses.clear();
        currentASTMMessage.setLength(0);
        astmState = ASTMState.IDLE;
        expectedFrameNumber = 1;
    }

    /**
     * Gets the time since last data was received.
     */
    public java.time.Duration timeSinceLastData() {
        return java.time.Duration.between(lastDataTime, Instant.now());
    }

    /**
     * Ensures the buffer has enough capacity.
     */
    private void ensureCapacity(int additionalBytes) {
        if (buffer.remaining() < additionalBytes) {
            int newCapacity = Math.min(buffer.capacity() * 2, MAX_CAPACITY);
            if (newCapacity <= buffer.capacity()) {
                // At max capacity - salvage complete messages before discarding incomplete data
                List<String> salvaged = new ArrayList<>(completedMessages);
                int discardedBytes = buffer.position();
                log.warn("SerialFrameBuffer at max capacity ({}MB), discarding {} bytes of incomplete message. " +
                        "Preserved {} complete messages.",
                    MAX_CAPACITY / 1024 / 1024, discardedBytes, salvaged.size());
                // Reset state but preserve completed messages
                currentASTMMessage.setLength(0);
                astmState = ASTMState.IDLE;
                buffer.clear();
                completedMessages.clear();
                completedMessages.addAll(salvaged);
                return;
            }
            ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
            buffer.flip();
            newBuffer.put(buffer);
            buffer = newBuffer;
            log.debug("Expanded buffer capacity to {} bytes", newCapacity);
        }
    }

    /**
     * Gets the current buffer size (bytes accumulated).
     */
    public int getBufferSize() {
        return buffer.position();
    }
}
