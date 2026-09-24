package org.itech.ahb.connectivity;

/** Performs the transport operation selected by an analyzer-scoped probe. */
public interface ConnectionProbeExecutor {

  ProbeCheck probeListener(int port);

  ProbeCheck probeRemote(String protocol, String host, int port, int timeoutMs);

  /** Whether the analyzer's host answers at all, for an analyzer that opens the connection itself. */
  ProbeCheck probeHost(String host, int timeoutMs);

  ProbeCheck probeDirectory(String path);

  ProbeCheck probeSerialDevice(String path);

  ProbeCheck probeHttpEndpoint(String baseUrl, int timeoutMs);
}
