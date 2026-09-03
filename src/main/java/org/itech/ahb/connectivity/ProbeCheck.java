package org.itech.ahb.connectivity;

import java.util.Map;

/** One stable, localizable observation made during a connection probe. */
public record ProbeCheck(
  String kind,
  String status,
  String code,
  long responseTimeMs,
  Map<String, Object> args
) {

  public ProbeCheck {
    args = args == null ? Map.of() : Map.copyOf(args);
  }
}
