package org.itech.ahb.connection;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bridge.connection-catalog")
public class AnalyzerConnectionCatalogProperties {

  private String directory = "/data/openelis-analyzer-bridge/connections";

  public String getDirectory() {
    return directory;
  }

  public void setDirectory(String directory) {
    this.directory = directory;
  }
}
