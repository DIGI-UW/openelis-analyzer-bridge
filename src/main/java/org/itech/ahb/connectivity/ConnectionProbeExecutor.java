package org.itech.ahb.connectivity;

/** Performs the transport operation selected by an analyzer-scoped probe. */
public interface ConnectionProbeExecutor {

  ProbeCheck probeListener(int port);

  ProbeCheck probeRemote(String protocol, String host, int port, int timeoutMs);

  ProbeCheck probeDirectory(String path);

  ProbeCheck probeSerialDevice(String path);

  ProbeCheck probeHttpEndpoint(String baseUrl, int timeoutMs);
}
