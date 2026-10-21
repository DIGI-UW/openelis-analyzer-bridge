package org.itech.ahb.connection;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.metrics.MetricsService;
import org.itech.ahb.util.IpLiteral;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which peers the shared analyzer listeners (ASTM and MLLP) accept a connection from. Address and
 * sender name attribute a message but do not authenticate it, so a listener reachable from the
 * internet can be limited to the addresses its analyzers connect from. A refused peer is closed
 * before anything is read from it.
 */
@Component
@Slf4j
public class ListenerSourcePolicy {

  /** Admits every peer. */
  public static final ListenerSourcePolicy OPEN = new ListenerSourcePolicy("", false, null);

  private final boolean restricted;
  private final List<Range> allowed;
  private final boolean knownHostsOnly;
  private final AnalyzerRuntimeRegistry registry;
  private final MetricsService metrics;

  public ListenerSourcePolicy(String allowedSources, boolean knownHostsOnly, AnalyzerRuntimeRegistry registry) {
    this(allowedSources, knownHostsOnly, registry, null);
  }

  @Autowired
  public ListenerSourcePolicy(
    @Value("${bridge.listeners.allowed-sources:}") String allowedSources,
    @Value("${bridge.listeners.admit-known-hosts-only:false}") boolean knownHostsOnly,
    AnalyzerRuntimeRegistry registry,
    MetricsService metrics
  ) {
    this.restricted = allowedSources != null && !allowedSources.isBlank();
    this.allowed = restricted ? parse(allowedSources) : List.of();
    this.knownHostsOnly = knownHostsOnly;
    this.registry = registry;
    this.metrics = metrics;
  }

  /** Whether {@code peer} may connect to the listener on {@code listenerPort}; a refusal is logged. */
  public boolean admits(int listenerPort, InetAddress peer) {
    String reason = null;
    if (restricted && allowed.stream().noneMatch(range -> range.contains(peer))) {
      reason = "it is not in bridge.listeners.allowed-sources";
    } else if (knownHostsOnly && (registry == null || !registry.hasHost(listenerPort, numericAddress(peer)))) {
      reason = "no active connection on this listener has it as its host";
    }
    if (reason == null) {
      return true;
    }
    log.warn("Listener on port {} refused a connection from {}: {}", listenerPort, peer.getHostAddress(), reason);
    if (metrics != null) metrics.recordListenerRefused(listenerPort);
    return false;
  }

  /** The peer's address without an IPv6 scope such as {@code %2}, as a connection's host is written. */
  private static String numericAddress(InetAddress peer) {
    try {
      return InetAddress.getByAddress(peer.getAddress()).getHostAddress();
    } catch (java.net.UnknownHostException exception) {
      return peer.getHostAddress();
    }
  }

  private static List<Range> parse(String allowedSources) {
    List<Range> ranges = new ArrayList<>();
    for (String entry : allowedSources.split(",")) {
      String value = entry.trim();
      if (value.isEmpty()) continue;
      Range range = Range.parse(value);
      if (range == null) {
        log.error("Ignoring bridge.listeners.allowed-sources entry '{}': not an IP address or CIDR range", value);
      } else {
        ranges.add(range);
      }
    }
    return List.copyOf(ranges);
  }

  /** An address and the number of leading bits that must match. */
  private record Range(byte[] network, int prefix) {
    static Range parse(String value) {
      int slash = value.indexOf('/');
      String address = IpLiteral.canonicalize(slash < 0 ? value : value.substring(0, slash));
      if (address == null) return null;
      byte[] bytes;
      try {
        bytes = InetAddress.getByName(address).getAddress();
      } catch (java.net.UnknownHostException e) {
        return null;
      }
      int prefix = bytes.length * 8;
      if (slash >= 0) {
        try {
          prefix = Integer.parseInt(value.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
          return null;
        }
        if (prefix < 0 || prefix > bytes.length * 8) return null;
      }
      return new Range(bytes, prefix);
    }

    boolean contains(InetAddress peer) {
      byte[] candidate = peer.getAddress();
      if (candidate.length != network.length) return false;
      int whole = prefix / 8;
      for (int index = 0; index < whole; index++) {
        if (candidate[index] != network[index]) return false;
      }
      int rest = prefix % 8;
      if (rest == 0) return true;
      int mask = (0xFF << (8 - rest)) & 0xFF;
      return (candidate[whole] & mask) == (network[whole] & mask);
    }
  }
}
