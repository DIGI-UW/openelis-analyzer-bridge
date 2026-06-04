package org.itech.ahb.mllp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Outbound MLLP client for HL7 v2 messages.
 *
 * <p>Opens a fresh TCP connection per send, frames the message with MLLP block
 * markers ({@code VT … FS CR}), writes it, reads back the framed ACK, and
 * inspects the MSA-1 acknowledgement code (AA/AE/AR) to classify the result.
 * Mirrors the raw-socket style of the
 * existing inbound {@link HapiMLLPListener} and outbound ASTM logic in
 * {@code AnalyzerQueryController} rather than pulling in HAPI's
 * {@code ca.uhn.hl7v2.app.Connection} which would require parsing every
 * payload into a typed {@code Message} before sending.
 *
 * <p>Connection-refused, timeout, and connection-closed failures are retried up
 * to three times with a 10 second back-off — matching the existing forward-side
 * retry shape in {@code DefaultForwardingHTTPToASTMHandler}. Deterministic
 * application rejects (MSA|AE / MSA|AR) are NOT retried: the analyzer already
 * received and rejected the message, so resending would only be rejected again.
 * A separate ticket tracks adding durable persistence (mirror of OGC-500).
 */
@Component
@Slf4j
public class OutboundMllpClient {

    static final byte VT = 0x0B;
    static final byte FS = 0x1C;
    static final byte CR = 0x0D;

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int MAX_ACK_BYTES = 1_000_000;
    private static final int RETRY_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 10_000L;

    public static final class SendResult {
        public final boolean success;
        public final String ackMessage;
        public final String error;
        public final int attempts;
        /** False for deterministic application rejects (MSA|AE / MSA|AR) — retrying won't help. */
        public final boolean retryable;

        public SendResult(boolean success, String ackMessage, String error, int attempts, boolean retryable) {
            this.success = success;
            this.ackMessage = ackMessage;
            this.error = error;
            this.attempts = attempts;
            this.retryable = retryable;
        }

        public SendResult(boolean success, String ackMessage, String error, int attempts) {
            this(success, ackMessage, error, attempts, true);
        }
    }

    public SendResult send(String host, int port, String hl7Message, int timeoutMs) {
        int effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        SendResult lastResult = null;
        for (int attempt = 1; attempt <= RETRY_ATTEMPTS; attempt++) {
            lastResult = attemptSend(host, port, hl7Message, effectiveTimeout, attempt);
            if (lastResult.success || !lastResult.retryable) {
                return lastResult;
            }
            if (attempt < RETRY_ATTEMPTS) {
                log.warn(
                        "MLLP outbound to {}:{} attempt {}/{} failed: {} — retrying in {}ms",
                        host, port, attempt, RETRY_ATTEMPTS, lastResult.error, RETRY_BACKOFF_MS);
                try {
                    Thread.sleep(RETRY_BACKOFF_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new SendResult(false, lastResult.ackMessage, "Interrupted during retry backoff", attempt);
                }
            }
        }
        return lastResult;
    }

    SendResult attemptSend(String host, int port, String hl7Message, int timeoutMs, int attempt) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(timeoutMs);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(VT);
            out.write(hl7Message.getBytes(StandardCharsets.UTF_8));
            out.write(FS);
            out.write(CR);
            out.flush();

            String ackText = readFramedAck(in, host, port);
            if (ackText == null) {
                return new SendResult(false, null, "Connection closed before MLLP terminator", attempt);
            }

            // MSA|<ack-code>|<msg-control-id>|...   AA = accept, AE = error, AR = reject
            if (containsAckCode(ackText, "AA")) {
                log.info("MLLP outbound to {}:{}: positive ACK (attempt {}/{})",
                        host, port, attempt, RETRY_ATTEMPTS);
                return new SendResult(true, ackText, null, attempt);
            }
            boolean appReject = containsAckCode(ackText, "AE") || containsAckCode(ackText, "AR");
            String error = containsAckCode(ackText, "AE")
                    ? "Application error ACK (MSA|AE)"
                    : containsAckCode(ackText, "AR")
                            ? "Application reject ACK (MSA|AR)"
                            : "ACK missing MSA segment or unrecognized code";
            log.warn("MLLP outbound to {}:{}: {}; ack head: {}", host, port, error,
                    ackText.length() > 200 ? ackText.substring(0, 200) : ackText);
            // AE/AR are deterministic application rejects — not retryable. A
            // missing/unrecognized MSA stays retryable (could be a transient read).
            return new SendResult(false, ackText, error, attempt, !appReject);

        } catch (SocketTimeoutException e) {
            return new SendResult(false, null, "Timeout: " + e.getMessage(), attempt);
        } catch (IOException e) {
            return new SendResult(false, null, e.getClass().getSimpleName() + ": " + e.getMessage(), attempt);
        }
    }

    private String readFramedAck(InputStream in, String host, int port) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        int b;
        boolean inFrame = false;
        int prev = -1;
        while ((b = in.read()) != -1) {
            if (!inFrame) {
                if (b == VT) {
                    inFrame = true;
                } else {
                    log.warn("MLLP outbound to {}:{}: expected VT, got 0x{}",
                            host, port, Integer.toHexString(b & 0xFF));
                }
                continue;
            }
            if (prev == FS && b == CR) {
                byte[] body = collected.toByteArray();
                // body currently includes the trailing FS — strip it
                int len = body.length;
                if (len > 0 && body[len - 1] == FS) {
                    len -= 1;
                }
                return new String(body, 0, len, StandardCharsets.UTF_8);
            }
            collected.write(b);
            prev = b;
            if (collected.size() > MAX_ACK_BYTES) {
                throw new IOException("MLLP ACK exceeded " + MAX_ACK_BYTES + " bytes");
            }
        }
        return null;
    }

    private static boolean containsAckCode(String ack, String code) {
        return ack.contains("MSA|" + code + "|") || ack.contains("MSA|" + code + "\r");
    }
}
