package org.itech.ahb.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * Every HTTP route requires HTTP Basic except the top-level health status.
 *
 * <p>Runs against a real server because MockMvc never performs the servlet container's ERROR
 * dispatch, which is what renders the body of a 401. Every actuator endpoint is exposed so the
 * check covers whatever an operator chooses to expose.
 */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "bridge.security.username=testuser",
    "bridge.security.password=testpass",
    "org.itech.ahb.mllp.enabled=false",
    "bridge.file.enabled=false",
    "management.endpoints.web.exposure.include=*"
  }
)
@Import(EndpointAuthorizationTest.CanaryController.class)
class EndpointAuthorizationTest {

  private static final String PUBLIC_ROUTE = "GET /actuator/health";
  /** Open without a credential: the pairing code authorizes pairing; HTTP input checks its sender. */
  private static final Set<String> OPEN_ROUTES = Set.of(PUBLIC_ROUTE, "GET /pairing", "POST /pairing", "POST /input");
  private static final String CREDENTIALS = "testuser:testpass";

  @DynamicPropertySource
  static void stateDirectories(DynamicPropertyRegistry registry) throws IOException {
    Path directory = Files.createTempDirectory("bridge-endpoint-authorization-test");
    registry.add("bridge.outbox.db-path", () -> directory.resolve("outbox.db").toString());
    registry.add("bridge.connection-catalog.directory", () -> directory.resolve("connections").toString());
    registry.add("bridge.profile-catalog.directory", () -> directory.resolve("profile-catalog").toString());
  }

  /** Stands in for an endpoint added later without any change to the security configuration. */
  @RestController
  static class CanaryController {

    @GetMapping("/test-canary")
    String canary() {
      return "reached";
    }
  }

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Autowired
  private ApplicationContext context;

  @LocalServerPort
  private int port;

  @Test
  void everyMappedRouteExceptHealthRejectsAnonymousCallers() throws Exception {
    Set<String> routes = mappedRoutes();
    Set<String> expected = Set.of(
      PUBLIC_ROUTE,
      "POST /api/orders",
      "POST /input",
      "GET /actuator/loggers",
      "GET /error",
      "GET /test-canary"
    );
    assertTrue(routes.containsAll(expected), () -> "Route enumeration is missing " + expected + ": " + routes);

    List<String> reachable = new ArrayList<>();
    for (String route : routes) {
      if (OPEN_ROUTES.contains(route)) {
        continue;
      }
      String method = route.substring(0, route.indexOf(' '));
      String path = concretePath(route.substring(route.indexOf(' ') + 1));
      try {
        HttpResponse<String> response = send(method, path, null, null);
        if (!isBasicChallenge(response)) {
          reachable.add(route + " -> " + response.statusCode());
        }
      } catch (IOException e) {
        reachable.add(route + " -> " + e);
      }
    }
    assertTrue(reachable.isEmpty(), () -> "Routes answered without credentials:\n" + String.join("\n", reachable));
  }

  @Test
  void theOpenRoutesGiveAnonymousCallersNothingBeyondTheirPurpose() throws Exception {
    assertEquals(403, send("POST", "/input", null, "H|\\^&\rL|1\r").statusCode(), "no active HTTP connection");
    assertEquals(200, send("GET", "/pairing", null, null).statusCode());
    assertEquals(503, send("POST", "/pairing", null, "{\"code\":\"x\"}").statusCode(), "pairing needs HTTPS");
  }

  @ParameterizedTest
  @CsvSource(
    {
      "GET, /no-such-path",
      "POST, /?forwardAddress=127.0.0.1&forwardPort=9",
      "POST, /api/query",
      "GET, /actuator/health/httpforward"
    }
  )
  void pathsOutsideTheHandlerMappingsRejectAnonymousCallers(String method, String path) throws Exception {
    HttpResponse<String> response = send(method, path, null, "POST".equals(method) ? "{}" : null);

    assertTrue(isBasicChallenge(response), () -> method + " " + path + " -> " + response.statusCode());
  }

  @Test
  void anonymousHealthShowsOnlyTheStatus() throws Exception {
    HttpResponse<String> response = send("GET", "/actuator/health", null, null);

    assertEquals(200, response.statusCode());
    JsonNode body = objectMapper.readTree(response.body());
    List<String> fields = new ArrayList<>();
    body.fieldNames().forEachRemaining(fields::add);
    assertEquals(List.of("status"), fields);
  }

  @Test
  void anonymousRejectionStillRendersAnErrorBody() throws Exception {
    HttpResponse<String> response = send("GET", "/no-such-path", null, null);

    assertEquals(401, response.statusCode());
    JsonNode body = objectMapper.readTree(response.body());
    assertEquals(401, body.path("status").asInt());
    assertEquals("/no-such-path", body.path("path").asText());
  }

  @Test
  void authenticatedUnknownPathIsNotFound() throws Exception {
    assertEquals(404, send("GET", "/no-such-path", CREDENTIALS, null).statusCode());
  }

  @Test
  void newEndpointIsProtectedWithoutSecurityChanges() throws Exception {
    assertEquals(401, send("GET", "/test-canary", null, null).statusCode());

    HttpResponse<String> authenticated = send("GET", "/test-canary", CREDENTIALS, null);
    assertEquals(200, authenticated.statusCode());
    assertEquals("reached", authenticated.body());
  }

  @Test
  void orderDispatchRequiresValidCredentials() throws Exception {
    assertEquals(401, send("POST", "/api/orders", "testuser:wrong", "{}").statusCode());

    HttpResponse<String> authenticated = send("POST", "/api/orders", CREDENTIALS, "{}");
    assertEquals(400, authenticated.statusCode());
    assertEquals("connectionId is required", objectMapper.readTree(authenticated.body()).path("error").asText());
  }

  @Test
  void analyzerQueryEndpointNoLongerExists() throws Exception {
    String target = "{\"host\":\"127.0.0.1\",\"port\":9,\"timeoutMs\":1000}";

    assertEquals(404, send("POST", "/api/query", CREDENTIALS, target).statusCode());
  }

  private Set<String> mappedRoutes() {
    Set<String> routes = new TreeSet<>();
    for (RequestMappingInfoHandlerMapping mapping : context
      .getBeansOfType(RequestMappingInfoHandlerMapping.class)
      .values()) {
      for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        for (String pattern : info.getPatternValues()) {
          if (methods.isEmpty()) {
            routes.add("GET " + pattern);
          }
          methods.forEach(method -> routes.add(method.name() + " " + pattern));
        }
      }
    }
    return routes;
  }

  private static String concretePath(String pattern) {
    return pattern.replaceAll("\\{[^}]*}", "1").replace("**", "x");
  }

  private static boolean isBasicChallenge(HttpResponse<String> response) {
    return (
      response.statusCode() == 401 && response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic")
    );
  }

  private HttpResponse<String> send(String method, String path, String credentials, String jsonBody)
    throws IOException, InterruptedException {
    HttpRequest.Builder request = HttpRequest
      .newBuilder(URI.create("http://localhost:" + port + path))
      .timeout(Duration.ofSeconds(15))
      .header("Accept", "application/json")
      .method(
        method,
        jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody)
      );
    if (jsonBody != null) {
      request.header("Content-Type", "application/json");
    }
    if (credentials != null) {
      String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
      request.header("Authorization", "Basic " + encoded);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
