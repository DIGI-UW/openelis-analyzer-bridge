package org.itech.ahb.connection;

import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;

/** The same saved protocol/transport contract applies to first receipt and retained-message replay. */
public final class AnalyzerInboundTransport {

  private AnalyzerInboundTransport() {}

  public static boolean matches(Protocol protocol, Transport transport, AnalyzerRuntimeRegistry.AnalyzerEntry entry) {
    if (entry == null || protocol == null || transport == null) return false;
    String savedProtocol = entry.getExpectedProtocol();
    String actualProtocol = protocol == Protocol.CSV ? "FILE" : protocol.name();
    if (!actualProtocol.equals(savedProtocol)) return false;
    String savedTransport = entry.getInboundTransport();
    if ("TCP/IP".equals(savedTransport)) {
      return "HL7".equals(savedProtocol) ? transport == Transport.MLLP : transport == Transport.TCP;
    }
    if ("RS-232".equals(savedTransport)) return transport == Transport.SERIAL;
    return transport.name().equals(savedTransport);
  }
}
