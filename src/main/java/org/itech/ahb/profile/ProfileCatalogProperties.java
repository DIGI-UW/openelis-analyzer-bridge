package org.itech.ahb.profile;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bridge.profile-catalog")
public class ProfileCatalogProperties {

  private String directory = System.getProperty("java.io.tmpdir") + "/openelis-analyzer-bridge/profiles";
  private String shippedPattern = "classpath*:/analyzer-profiles/**/*.json";

  public String getDirectory() {
    return directory;
  }

  public void setDirectory(String directory) {
    this.directory = directory;
  }

  public String getShippedPattern() {
    return shippedPattern;
  }

  public void setShippedPattern(String shippedPattern) {
    this.shippedPattern = shippedPattern;
  }
}
