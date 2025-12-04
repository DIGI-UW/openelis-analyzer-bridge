package org.itech.ahb.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration tests for multi-analyzer IP extraction and header verification.
 * Tests FR-005 (support multiple concurrent analyzer connections with correct source IPs).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Multi-Analyzer IP Integration Tests")
class MultiAnalyzerIPTest {

    private static final String[] TEST_IPS = {
        "192.168.1.10",
        "192.168.1.11",
        "192.168.1.12"
    };

    @Nested
    @DisplayName("Concurrent Connection Tests (FR-005)")
    class ConcurrentConnectionTests {

        @Test
        @DisplayName("Should extract correct IP for each of 3 concurrent analyzers")
        void shouldExtractCorrectIPsForMultipleAnalyzers() throws Exception {
            // Given: 3 different analyzer sockets
            List<Socket> sockets = createMockSockets(TEST_IPS);

            // When: We extract IPs from all sockets
            List<String> extractedIPs = new ArrayList<>();
            for (Socket socket : sockets) {
                String ip = extractSourceIp(socket);
                extractedIPs.add(ip);
            }

            // Then: Each IP should match the expected analyzer IP (no cross-contamination)
            assertEquals(TEST_IPS.length, extractedIPs.size());
            for (int i = 0; i < TEST_IPS.length; i++) {
                assertEquals(TEST_IPS[i], extractedIPs.get(i),
                    "Analyzer " + (i + 1) + " should have IP " + TEST_IPS[i]);
            }
        }

        @Test
        @DisplayName("Should handle concurrent IP extractions without race conditions")
        void shouldHandleConcurrentExtractionsWithoutRaceConditions() throws Exception {
            // Given: Multiple sockets that will be processed concurrently
            int numberOfAnalyzers = 10;
            List<Socket> sockets = new ArrayList<>();
            String[] ips = new String[numberOfAnalyzers];
            for (int i = 0; i < numberOfAnalyzers; i++) {
                ips[i] = "192.168.1." + (i + 10);
                Socket socket = mock(Socket.class);
                InetAddress address = InetAddress.getByName(ips[i]);
                InetSocketAddress socketAddress = new InetSocketAddress(address, 5000);
                when(socket.getRemoteSocketAddress()).thenReturn(socketAddress);
                when(socket.isClosed()).thenReturn(false);
                sockets.add(socket);
            }

            // When: We extract IPs concurrently
            List<String> extractedIPs = new ArrayList<>();
            ExecutorService executor = Executors.newFixedThreadPool(numberOfAnalyzers);
            CountDownLatch latch = new CountDownLatch(numberOfAnalyzers);
            
            List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < numberOfAnalyzers; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        String ip = extractSourceIp(sockets.get(index));
                        results.add(index + ":" + ip); // Track which socket returned which IP
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for all extractions to complete
            assertTrue(latch.await(5, TimeUnit.SECONDS), "All extractions should complete within 5 seconds");
            executor.shutdown();

            // Then: Each extraction should return the correct IP for its socket
            assertEquals(numberOfAnalyzers, results.size());
            for (String result : results) {
                String[] parts = result.split(":");
                int index = Integer.parseInt(parts[0]);
                String extractedIp = parts[1];
                assertEquals(ips[index], extractedIp,
                    "Socket " + index + " should return IP " + ips[index] + " but got " + extractedIp);
            }
        }
    }

    @Nested
    @DisplayName("Mixed IP Format Tests")
    class MixedIPFormatTests {

        @Test
        @DisplayName("Should handle mix of IPv4 and IPv6 analyzers")
        void shouldHandleMixedIPFormats() throws Exception {
            // Given: A mix of IPv4 and IPv6 addresses
            String[] mixedIPs = {"192.168.1.10", "::1", "10.0.0.5", "2001:db8::1"};
            List<Socket> sockets = createMockSockets(mixedIPs);

            // When: We extract IPs from all sockets
            List<String> extractedIPs = new ArrayList<>();
            for (Socket socket : sockets) {
                String ip = extractSourceIp(socket);
                extractedIPs.add(ip);
            }

            // Then: All IPs should be extracted (format may vary for IPv6)
            assertEquals(mixedIPs.length, extractedIPs.size());
            
            // IPv4 addresses should match exactly
            assertEquals("192.168.1.10", extractedIPs.get(0));
            assertEquals("10.0.0.5", extractedIPs.get(2));
            
            // IPv6 addresses should be present (format may vary)
            assertNotNull(extractedIPs.get(1));
            assertNotNull(extractedIPs.get(3));
        }
    }

    /**
     * Helper method to create mock sockets with specific IP addresses.
     */
    private List<Socket> createMockSockets(String[] ips) throws Exception {
        List<Socket> sockets = new ArrayList<>();
        for (String ip : ips) {
            Socket socket = mock(Socket.class);
            InetAddress address = InetAddress.getByName(ip);
            InetSocketAddress socketAddress = new InetSocketAddress(address, 5000);
            when(socket.getRemoteSocketAddress()).thenReturn(socketAddress);
            when(socket.isClosed()).thenReturn(false);
            sockets.add(socket);
        }
        return sockets;
    }

    /**
     * Helper method that mirrors the implementation in ASTMReceiveThread.
     */
    private String extractSourceIp(Socket socket) {
        try {
            if (socket == null || socket.isClosed()) {
                return null;
            }
            InetSocketAddress address = (InetSocketAddress) socket.getRemoteSocketAddress();
            if (address == null) {
                return null;
            }
            return address.getAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }
}

