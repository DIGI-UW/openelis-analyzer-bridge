package org.itech.ahb.order;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sends an ASTM LIS2-A2 order to an analyzer over TCP: ENQ → framed records →
 * EOT, per CLSI LIS1-A. Mirrors the framing in {@code AnalyzerQueryController}
 * (STX + frame# + content + ETX + checksum + CR + LF), but for the LIS-initiated
 * order direction (no response expected; the analyzer pushes results back on its
 * own inbound channel).
 */
@Component
@Slf4j
public class OutboundAstmClient {

    private static final byte ENQ = 0x05;
    private static final byte ACK = 0x06;
    private static final byte EOT = 0x04;
    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;
    private static final byte CR = 0x0D;
    private static final byte LF = 0x0A;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    public boolean send(String host, int port, List<String> records, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(timeoutMs > 0 ? timeoutMs : 30000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(ENQ);
            out.flush();
            int resp = in.read();
            if (resp == ENQ) {
                // Line contention (CLSI LIS1-A §8.2.7.1): the instrument also
                // asserted ENQ (e.g. a GeneXpert with queued results). The LIS
                // holds priority when it has an active order, so the instrument
                // yields and ACKs — consume that ENQ and read its ACK.
                log.debug("ASTM order to {}:{} — ENQ contention; instrument yielding, awaiting ACK", host, port);
                resp = in.read();
            }
            if (resp != ACK) {
                log.warn("ASTM order to {}:{} — ENQ not ACKed (got 0x{})", host, port,
                        Integer.toHexString(resp & 0xFF));
                return false;
            }

            int frameNumber = 0;
            for (String record : records) {
                frameNumber = (frameNumber + 1) % 8;
                if (!sendFrame(out, in, record, frameNumber, host, port)) {
                    return false;
                }
            }

            out.write(EOT);
            out.flush();
            log.info("ASTM order sent to {}:{} ({} records)", host, port, records.size());
            return true;
        } catch (IOException e) {
            log.warn("ASTM order to {}:{} failed: {}", host, port, e.getMessage());
            return false;
        }
    }

    private boolean sendFrame(OutputStream out, InputStream in, String content, int frameNumber,
            String host, int port) throws IOException {
        byte[] fn = String.valueOf(frameNumber).getBytes(StandardCharsets.UTF_8);
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        int checksum = 0;
        for (byte b : fn) checksum += b & 0xFF;
        for (byte b : body) checksum += b & 0xFF;
        checksum += ETX;
        checksum %= 256;

        out.write(STX);
        out.write(fn);
        out.write(body);
        out.write(ETX);
        out.write(String.format("%02X", checksum).getBytes(StandardCharsets.UTF_8));
        out.write(CR);
        out.write(LF);
        out.flush();

        int resp = in.read();
        if (resp != ACK) {
            log.warn("ASTM order to {}:{} — frame {} not ACKed (got 0x{})", host, port, frameNumber,
                    Integer.toHexString(resp & 0xFF));
            return false;
        }
        return true;
    }
}
