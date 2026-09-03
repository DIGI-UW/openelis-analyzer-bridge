package org.itech.ahb.serial;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.itech.ahb.model.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SerialFrameBuffer class.
 * <p>
 * Tests ASTM and HL7 (MLLP) message framing without requiring physical serial ports.
 * </p>
 */
class SerialFrameBufferTest {

    private SerialFrameBuffer buffer;

    // ASTM control characters
    private static final byte ENQ = 0x05;
    private static final byte ACK = 0x06;
    private static final byte NAK = 0x15;
    private static final byte EOT = 0x04;
    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;
    private static final byte ETB = 0x17;
    private static final byte CR = 0x0D;
    private static final byte LF = 0x0A;

    // HL7 MLLP control characters
    private static final byte VT = 0x0B;
    private static final byte FS = 0x1C;

    @Nested
    @DisplayName("ASTM Protocol Tests")
    class ASTMProtocolTests {

        @BeforeEach
        void setUp() {
            buffer = new SerialFrameBuffer(Protocol.ASTM);
        }

        @Test
        @DisplayName("Should respond with ACK when receiving ENQ")
        void shouldRespondWithAckOnEnq() {
            List<byte[]> responses = buffer.appendData(new byte[]{ENQ});

            assertEquals(1, responses.size());
            assertArrayEquals(new byte[]{ACK}, responses.get(0));
            assertEquals(SerialFrameBuffer.ASTMState.ESTABLISHED, buffer.getAstmState());
        }

        @Test
        @DisplayName("Should transition to ESTABLISHED state after ENQ")
        void shouldTransitionToEstablishedStateAfterEnq() {
            buffer.appendData(new byte[]{ENQ});
            assertEquals(SerialFrameBuffer.ASTMState.ESTABLISHED, buffer.getAstmState());
        }

        @Test
        @DisplayName("Should complete message on EOT")
        void shouldCompleteMessageOnEot() {
            // Send ENQ
            buffer.appendData(new byte[]{ENQ});

            // Send a simple frame: <STX>1H|\^&|||\r<ETX>checksum<CR><LF>
            String text = "H|\\^&|||";
            byte[] frame = buildASTMFrame(1, text, true);
            buffer.appendData(frame);

            // Send EOT
            buffer.appendData(new byte[]{EOT});

            assertTrue(buffer.hasCompletedMessages());
            List<String> messages = buffer.getCompletedMessages();
            assertEquals(1, messages.size());
            assertEquals(text, messages.get(0));
        }

        @Test
        @DisplayName("Should handle multiple frames in a message")
        void shouldHandleMultipleFrames() {
            // Send ENQ
            buffer.appendData(new byte[]{ENQ});

            // Send first frame (intermediate - using ETB)
            String text1 = "H|\\^&|||TEST";
            byte[] frame1 = buildASTMFrame(1, text1, false);
            List<byte[]> responses1 = buffer.appendData(frame1);
            assertEquals(1, responses1.size());
            assertArrayEquals(new byte[]{ACK}, responses1.get(0));

            // Send second frame (final - using ETX)
            String text2 = "L|1|N";
            byte[] frame2 = buildASTMFrame(2, text2, true);
            List<byte[]> responses2 = buffer.appendData(frame2);
            assertEquals(1, responses2.size());
            assertArrayEquals(new byte[]{ACK}, responses2.get(0));

            // Send EOT
            buffer.appendData(new byte[]{EOT});

            assertTrue(buffer.hasCompletedMessages());
            List<String> messages = buffer.getCompletedMessages();
            assertEquals(1, messages.size());
            assertEquals(text1 + text2, messages.get(0));
        }

        @Test
        @DisplayName("Should send NAK on bad checksum")
        void shouldSendNakOnBadChecksum() {
            buffer.appendData(new byte[]{ENQ});

            // Send frame with bad checksum
            String text = "H|\\^&|||";
            byte[] frame = buildASTMFrame(1, text, true);
            // Corrupt the checksum
            frame[frame.length - 4] = 'X';
            frame[frame.length - 3] = 'X';

            List<byte[]> responses = buffer.appendData(frame);
            assertEquals(1, responses.size());
            assertArrayEquals(new byte[]{NAK}, responses.get(0));
        }

        @Test
        @DisplayName("Should handle frame numbers wrapping around")
        void shouldHandleFrameNumberWraparound() {
            buffer.appendData(new byte[]{ENQ});

            // Send frames 1-8 (should wrap to 1 after 7 -> 0 -> 1)
            for (int i = 1; i <= 8; i++) {
                int frameNum = i % 8;
                if (frameNum == 0) frameNum = 8;
                frameNum = ((i - 1) % 8) + 1;

                String text = "R|" + i + "|test";
                boolean isFinal = (i == 8);
                byte[] frame = buildASTMFrame(frameNum > 7 ? frameNum % 8 : frameNum, text, isFinal);

                // For frame numbers, ASTM uses 1-7, then wraps to 0, then 1-7 again
                int expectedFrameNum = ((i - 1) % 8) + 1;
                if (expectedFrameNum > 7) expectedFrameNum = expectedFrameNum % 8;

                List<byte[]> responses = buffer.appendData(frame);
                assertEquals(1, responses.size(), "Frame " + i + " should receive response");
            }

            buffer.appendData(new byte[]{EOT});
            assertTrue(buffer.hasCompletedMessages());
        }

        @Test
        @DisplayName("Should reset state properly")
        void shouldResetStateProperly() {
            buffer.appendData(new byte[]{ENQ});
            buffer.appendData(buildASTMFrame(1, "H|\\^&|||", false));

            buffer.reset();

            assertEquals(SerialFrameBuffer.ASTMState.IDLE, buffer.getAstmState());
            assertEquals(0, buffer.getBufferSize());
            assertFalse(buffer.hasCompletedMessages());
        }

        @Test
        @DisplayName("Should handle non-compliant mode (no ENQ)")
        void shouldHandleNonCompliantMode() {
            // Send STX directly without ENQ (non-compliant mode)
            byte[] frame = buildASTMFrame(1, "H|\\^&|||TEST", true);

            buffer.appendData(frame);
            buffer.appendData(new byte[]{EOT});

            // In non-compliant mode, should still process
            assertEquals(SerialFrameBuffer.ASTMState.IDLE, buffer.getAstmState());
        }

        /**
         * Builds an ASTM frame with proper checksum.
         */
        private byte[] buildASTMFrame(int frameNumber, String text, boolean isFinal) {
            byte terminator = isFinal ? ETX : ETB;
            int checksum = 0;

            // Checksum includes frame number, text, and terminator
            checksum += (byte) Character.forDigit(frameNumber % 8, 10);
            for (byte b : text.getBytes()) {
                checksum += b;
            }
            checksum += terminator;
            checksum %= 256;
            String checksumStr = String.format("%02X", checksum);

            // Build frame: <STX><FN><text><ETX/ETB><C1><C2><CR><LF>
            byte[] frame = new byte[1 + 1 + text.length() + 1 + 2 + 2];
            int pos = 0;
            frame[pos++] = STX;
            frame[pos++] = (byte) Character.forDigit(frameNumber % 8, 10);
            System.arraycopy(text.getBytes(), 0, frame, pos, text.length());
            pos += text.length();
            frame[pos++] = terminator;
            frame[pos++] = (byte) checksumStr.charAt(0);
            frame[pos++] = (byte) checksumStr.charAt(1);
            frame[pos++] = CR;
            frame[pos] = LF;

            return frame;
        }
    }

    @Nested
    @DisplayName("HL7 MLLP Protocol Tests")
    class HL7MLLPProtocolTests {

        @BeforeEach
        void setUp() {
            buffer = new SerialFrameBuffer(Protocol.HL7);
        }

        @Test
        @DisplayName("Should receive complete HL7 message")
        void shouldReceiveCompleteHL7Message() {
            String hl7Message = "MSH|^~\\&|TEST|LAB|OPENELIS|LAB|20260205120000||ORU^R01|MSG001|P|2.5.1\r" +
                               "PID|1||12345||DOE^JOHN\r" +
                               "OBX|1|NM|WBC||7.5|10^3/uL";

            byte[] mllpMessage = wrapMLLP(hl7Message);
            List<byte[]> responses = buffer.appendData(mllpMessage);

            // Should get an ACK response
            assertTrue(responses.size() > 0);

            assertTrue(buffer.hasCompletedMessages());
            List<String> messages = buffer.getCompletedMessages();
            assertEquals(1, messages.size());
            assertEquals(hl7Message, messages.get(0));
        }

        @Test
        @DisplayName("Should handle fragmented MLLP message")
        void shouldHandleFragmentedMLLPMessage() {
            String hl7Message = "MSH|^~\\&|TEST|LAB|OPENELIS|LAB|20260205120000||ORU^R01|MSG001|P|2.5.1";

            // Send in fragments
            buffer.appendData(new byte[]{VT});
            buffer.appendData(hl7Message.substring(0, 10).getBytes());
            buffer.appendData(hl7Message.substring(10).getBytes());
            buffer.appendData(new byte[]{FS, CR});

            assertTrue(buffer.hasCompletedMessages());
            List<String> messages = buffer.getCompletedMessages();
            assertEquals(1, messages.size());
            assertEquals(hl7Message, messages.get(0));
        }

        @Test
        @DisplayName("Should handle multiple HL7 messages")
        void shouldHandleMultipleHL7Messages() {
            String msg1 = "MSH|^~\\&|TEST|||20260205120000||ORU^R01|MSG001|P|2.5.1";
            String msg2 = "MSH|^~\\&|TEST|||20260205120001||ORU^R01|MSG002|P|2.5.1";

            buffer.appendData(wrapMLLP(msg1));
            buffer.appendData(wrapMLLP(msg2));

            List<String> messages = buffer.getCompletedMessages();
            assertEquals(2, messages.size());
            assertEquals(msg1, messages.get(0));
            assertEquals(msg2, messages.get(1));
        }

        @Test
        @DisplayName("Should generate HL7 ACK response")
        void shouldGenerateHL7AckResponse() {
            String hl7Message = "MSH|^~\\&|TEST|LAB|OPENELIS|LAB|20260205120000||ORU^R01|CTRL123|P|2.5.1";

            List<byte[]> responses = buffer.appendData(wrapMLLP(hl7Message));

            assertTrue(responses.size() > 0);
            String response = new String(responses.get(0));
            assertTrue(response.contains("MSH|^~\\&|BRIDGE|BRIDGE"));
            assertTrue(response.contains("MSA|AA|CTRL123"));
        }

        @Test
        @DisplayName("Should ignore data before VT")
        void shouldIgnoreDataBeforeVT() {
            String garbage = "random garbage";
            String hl7Message = "MSH|^~\\&|TEST|||20260205120000||ORU^R01|MSG001|P|2.5.1";

            buffer.appendData(garbage.getBytes());
            buffer.appendData(wrapMLLP(hl7Message));

            assertTrue(buffer.hasCompletedMessages());
            List<String> messages = buffer.getCompletedMessages();
            assertEquals(1, messages.size());
            assertEquals(hl7Message, messages.get(0));
        }

        /**
         * Wraps a message in MLLP framing.
         */
        private byte[] wrapMLLP(String message) {
            byte[] msgBytes = message.getBytes();
            byte[] wrapped = new byte[msgBytes.length + 3];
            wrapped[0] = VT;
            System.arraycopy(msgBytes, 0, wrapped, 1, msgBytes.length);
            wrapped[msgBytes.length + 1] = FS;
            wrapped[msgBytes.length + 2] = CR;
            return wrapped;
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle null data")
        void shouldHandleNullData() {
            buffer = new SerialFrameBuffer(Protocol.ASTM);
            List<byte[]> responses = buffer.appendData(null);
            assertTrue(responses.isEmpty());
        }

        @Test
        @DisplayName("Should handle empty data")
        void shouldHandleEmptyData() {
            buffer = new SerialFrameBuffer(Protocol.ASTM);
            List<byte[]> responses = buffer.appendData(new byte[0]);
            assertTrue(responses.isEmpty());
        }

        @Test
        @DisplayName("Should track time since last data")
        void shouldTrackTimeSinceLastData() throws InterruptedException {
            buffer = new SerialFrameBuffer(Protocol.ASTM);

            buffer.appendData(new byte[]{ENQ});
            Thread.sleep(100);

            assertTrue(buffer.timeSinceLastData().toMillis() >= 100);
        }

        @Test
        @DisplayName("Should handle buffer overflow gracefully")
        void shouldHandleBufferOverflowGracefully() {
            buffer = new SerialFrameBuffer(Protocol.HL7);

            // Send VT to start message
            buffer.appendData(new byte[]{VT});

            // Send lots of data without ending
            byte[] largeData = new byte[10000];
            for (int i = 0; i < largeData.length; i++) {
                largeData[i] = 'X';
            }

            // Should not throw exception
            assertDoesNotThrow(() -> {
                for (int i = 0; i < 100; i++) {
                    buffer.appendData(largeData);
                }
            });
        }
    }
}
