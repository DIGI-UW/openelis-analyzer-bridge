package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Applies one durable, profile-pinned connection to Bridge runtime. */
public interface AnalyzerConnectionRuntime {

  void activate(ObjectNode connection, ObjectNode profile);

  void deactivate(ObjectNode connection, ObjectNode profile);

  void restore(ObjectNode connection, ObjectNode profile);

  static AnalyzerConnectionRuntime noOp() {
    return new AnalyzerConnectionRuntime() {
      @Override
      public void activate(ObjectNode connection, ObjectNode profile) {}

      @Override
      public void deactivate(ObjectNode connection, ObjectNode profile) {}

      @Override
      public void restore(ObjectNode connection, ObjectNode profile) {}
    };
  }
}
