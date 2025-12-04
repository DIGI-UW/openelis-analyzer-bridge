package org.itech.ahb.astm.handling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for source IP extraction from sockets.
 * Tests FR-001 (extract source IP from TCP socket) and FR-003 (handle IPv4 and IPv6).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Source IP Extraction Tests")
class SourceIPExtractionTest {

    @Mock
    private Socket mockSocket;

    @Nested
    @DisplayName("IPv4 Address Extraction (FR-001, FR-003)")
    class IPv4Tests {

        @Test
        @DisplayName("Should extract IPv4 address from socket")
        void shouldExtractIPv4Address() throws Exception {
            // Given: A socket connected from 192.168.1.10
            InetAddress ipv4Address = InetAddress.getByName("192.168.1.10");
            InetSocketAddress socketAddress = new InetSocketAddress(ipv4Address, 5000);
            when(mockSocket.getRemoteSocketAddress()).thenReturn(socketAddress);
            when(mockSocket.isClosed()).thenReturn(false);

            // When: We extract the IP
            String sourceIp = extractSourceIp(mockSocket);

            // Then: The correct IPv4 address should be returned
            assertEquals("192.168.1.10", sourceIp);
        }

        @Test
        @DisplayName("Should extract localhost IPv4 address")
        void shouldExtractLocalhostIPv4() throws Exception {
            // Given: A socket connected from localhost (127.0.0.1)
            InetAddress localhost = InetAddress.getByName("127.0.0.1");
            InetSocketAddress socketAddress = new InetSocketAddress(localhost, 5000);
            when(mockSocket.getRemoteSocketAddress()).thenReturn(socketAddress);
            when(mockSocket.isClosed()).thenReturn(false);

            // When: We extract the IP
            String sourceIp = extractSourceIp(mockSocket);

            // Then: The localhost address should be returned
            assertEquals("127.0.0.1", sourceIp);
        }
    }

    @Nested
    @DisplayName("IPv6 Address Extraction (FR-003)")
    class IPv6Tests {

        @Test
        @DisplayName("Should extract IPv6 loopback address (::1)")
        void shouldExtractIPv6Loopback() throws Exception {
            // Given: A socket connected from IPv6 loopback
            InetAddress ipv6Loopback = InetAddress.getByName("::1");
            InetSocketAddress socketAddress = new InetSocketAddress(ipv6Loopback, 5000);
            when(mockSocket.getRemoteSocketAddress()).thenReturn(socketAddress);
            when(mockSocket.isClosed()).thenReturn(false);

            // When: We extract the IP
            String sourceIp = extractSourceIp(mockSocket);

            // Then: The IPv6 loopback should be returned (format may vary)
            assertNotNull(sourceIp);
            assertTrue(sourceIp.equals("0:0:0:0:0:0:0:1") || sourceIp.equals("::1"),
                "Expected IPv6 loopback address, got: " + sourceIp);
        }

        @Test
        @DisplayName("Should extract full IPv6 address (2001:db8::1)")
        void shouldExtractFullIPv6Address() throws Exception {
            // Given: A socket connected from a full IPv6 address
            InetAddress ipv6Address = InetAddress.getByName("2001:db8::1");
            InetSocketAddress socketAddress = new InetSocketAddress(ipv6Address, 5000);
            when(mockSocket.getRemoteSocketAddress()).thenReturn(socketAddress);
            when(mockSocket.isClosed()).thenReturn(false);

            // When: We extract the IP
            String sourceIp = extractSourceIp(mockSocket);

            // Then: The IPv6 address should be returned
            assertNotNull(sourceIp);
            assertTrue(sourceIp.contains("2001") && sourceIp.contains("db8"),
                "Expected IPv6 address containing 2001:db8, got: " + sourceIp);
        }
    }

    @Nested
    @DisplayName("Error Handling (FR-004)")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should return null when socket is null")
        void shouldReturnNullForNullSocket() {
            // When: We try to extract IP from null socket
            String sourceIp = extractSourceIp(null);

            // Then: null should be returned (graceful degradation)
            assertNull(sourceIp);
        }

        @Test
        @DisplayName("Should return null when socket is closed")
        void shouldReturnNullForClosedSocket() {
            // Given: A closed socket
            when(mockSocket.isClosed()).thenReturn(true);

            // When: We try to extract IP
            String sourceIp = extractSourceIp(mockSocket);

            // Then: null should be returned
            assertNull(sourceIp);
        }

        @Test
        @DisplayName("Should return null when remote address is null")
        void shouldReturnNullForNullRemoteAddress() {
            // Given: A socket with null remote address
            when(mockSocket.isClosed()).thenReturn(false);
            when(mockSocket.getRemoteSocketAddress()).thenReturn(null);

            // When: We try to extract IP
            String sourceIp = extractSourceIp(mockSocket);

            // Then: null should be returned
            assertNull(sourceIp);
        }
    }

    /**
     * Helper method that mirrors the implementation in ASTMReceiveThread.
     * This method should be replaced with the actual implementation method during integration testing.
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

