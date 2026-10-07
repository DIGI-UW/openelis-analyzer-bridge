package org.itech.ahb.pairing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Points HTTPS at the Bridge's generated identity when no keystore is mounted, before the web
 * server reads its SSL settings. A mounted keystore that exists is used as it is.
 */
public class BridgeIdentityEnvironment implements EnvironmentPostProcessor {

  static final String DEFAULT_DIRECTORY = "/data/openelis-analyzer-bridge/identity";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    if (!Boolean.parseBoolean(resolve(environment, "server.ssl.enabled"))) {
      return;
    }
    String mounted = resolve(environment, "server.ssl.key-store");
    if (mounted != null && !mounted.isBlank() && exists(mounted)) {
      return;
    }
    String directory = resolve(environment, "bridge.identity.directory");
    BridgeIdentity.Keystore keystore = BridgeIdentity.ensureGenerated(
      Path.of(directory == null || directory.isBlank() ? DEFAULT_DIRECTORY : directory)
    );
    String password = new String(keystore.password());
    Map<String, Object> generated = new HashMap<>();
    generated.put("server.ssl.key-store", keystore.path().toString());
    generated.put("server.ssl.key-store-type", keystore.type());
    generated.put("server.ssl.key-store-password", password);
    // The production profile reads these through placeholders.
    generated.put("server.ssl.keyStorePath", keystore.path().toString());
    generated.put("server.ssl.keyStoreType", keystore.type());
    generated.put("server.ssl.keyStorePassword", password);
    environment.getPropertySources().addFirst(new MapPropertySource("bridgeGeneratedIdentity", generated));
  }

  private static String resolve(ConfigurableEnvironment environment, String key) {
    try {
      return environment.getProperty(key);
    } catch (IllegalArgumentException unresolvedPlaceholder) {
      return null;
    }
  }

  private static boolean exists(String location) {
    if (location.startsWith("classpath:")) {
      return true;
    }
    String path = location.startsWith("file:") ? location.substring("file:".length()) : location;
    return Files.isRegularFile(Path.of(path));
  }
}
