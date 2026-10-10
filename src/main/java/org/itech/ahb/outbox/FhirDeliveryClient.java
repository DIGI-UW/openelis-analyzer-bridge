package org.itech.ahb.outbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.util.HttpClientFactory;
import org.springframework.stereotype.Component;

/**
 * Sends one normalized bundle to OpenELIS. One attempt per call: retry scheduling belongs to the
 * outbox dispatcher, which can survive a restart, rather than to a loop inside a request thread.
 */
@Component
@Slf4j
public class FhirDeliveryClient {

  private final HTTPForwardServerConfigurationProperties httpConfig;
  private final HttpClient httpClient;
  private final int readTimeoutSeconds;
  private final int maxResponseBytes;
  private final org.itech.ahb.pairing.OpenElisClients openElis;
  private HttpClient pairedClient;
  private javax.net.ssl.SSLContext pairedContext;

  public FhirDeliveryClient(HTTPForwardServerConfigurationProperties httpConfig) {
    this(httpConfig, null);
  }

  /** Delivers with the paired TLS identity once the Bridge is paired, and as configured before. */
  @org.springframework.beans.factory.annotation.Autowired
  public FhirDeliveryClient(
    HTTPForwardServerConfigurationProperties httpConfig,
    org.itech.ahb.pairing.OpenElisClients openElis
  ) {
    this.openElis = openElis;
    this.httpConfig = httpConfig;
    this.readTimeoutSeconds = httpConfig.getReadTimeoutSeconds();
    this.maxResponseBytes = httpConfig.getMaxResponseBytes();
    this.httpClient = HttpClientFactory.create(
      httpConfig.getConnectTimeoutSeconds(),
      httpConfig.isInsecureTls(),
      "forwarding"
    );
    log.info(
      "FHIR delivery client: target={} connect={}s read={}s",
      targetUri(),
      httpConfig.getConnectTimeoutSeconds(),
      readTimeoutSeconds
    );
  }

  /** POST the bundle once and report what came back, or what stopped it. */
  public DeliveryOutcome deliver(String fhirJson) {
    try {
      HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(targetUri())
        .header("Content-Type", "application/fhir+json")
        .timeout(Duration.ofSeconds(readTimeoutSeconds))
        .POST(HttpRequest.BodyPublishers.ofString(fhirJson));
      HttpClient client = pairedClient();
      // Once paired, OpenELIS authenticates the Bridge by its certificate; no password is sent.
      if (client == null && httpConfig.getUsername() != null && !httpConfig.getUsername().isEmpty()) {
        addBasicAuth(builder, httpConfig.getUsername(), httpConfig.getPassword());
      }
      if (client == null) {
        client = httpClient;
      }
      // The request timeout ends at the response headers; waiting on the whole exchange also bounds
      // the time spent reading the body. The wait covers connecting too, so an unreachable OpenELIS
      // is reported by its own connect failure rather than as a slow answer.
      CompletableFuture<HttpResponse<String>> exchange = client.sendAsync(
        builder.build(),
        info -> limitedBody(maxResponseBytes)
      );
      try {
        HttpResponse<String> response = exchange.get(
          (long) httpConfig.getConnectTimeoutSeconds() + readTimeoutSeconds,
          TimeUnit.SECONDS
        );
        return DeliveryOutcome.responded(response.statusCode(), response.body());
      } catch (TimeoutException e) {
        exchange.cancel(true);
        return DeliveryOutcome.failed(
          new HttpTimeoutException("OpenELIS response not complete within " + readTimeoutSeconds + "s")
        );
      } catch (ExecutionException e) {
        return DeliveryOutcome.failed(e.getCause() instanceof Exception cause ? cause : e);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return DeliveryOutcome.failed(e);
    } catch (Exception e) {
      return DeliveryOutcome.failed(e);
    }
  }

  private synchronized HttpClient pairedClient() {
    javax.net.ssl.SSLContext context = openElis == null ? null : openElis.sslContext().orElse(null);
    if (context == null) {
      return null;
    }
    if (context != pairedContext) {
      pairedClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(httpConfig.getConnectTimeoutSeconds()))
        .sslContext(context)
        .build();
      pairedContext = context;
    }
    return pairedClient;
  }

  /** Collects a response body as UTF-8 text, failing once it grows past {@code limit} bytes. */
  private static HttpResponse.BodySubscriber<String> limitedBody(int limit) {
    return new HttpResponse.BodySubscriber<>() {
      private final CompletableFuture<String> body = new CompletableFuture<>();
      private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      private Flow.Subscription subscription;

      @Override
      public CompletionStage<String> getBody() {
        return body;
      }

      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(List<ByteBuffer> items) {
        if (body.isDone()) return;
        for (ByteBuffer item : items) {
          if (bytes.size() + item.remaining() > limit) {
            subscription.cancel();
            body.completeExceptionally(new IOException("OpenELIS response exceeded " + limit + " bytes"));
            return;
          }
          byte[] chunk = new byte[item.remaining()];
          item.get(chunk);
          bytes.write(chunk, 0, chunk.length);
        }
      }

      @Override
      public void onError(Throwable failure) {
        body.completeExceptionally(failure);
      }

      @Override
      public void onComplete() {
        body.complete(bytes.toString(StandardCharsets.UTF_8));
      }
    };
  }

  /** OpenELIS's analyzer result endpoint, {@code {base}/analyzer/fhir}. */
  public URI targetUri() {
    return httpConfig.resolve("/analyzer/fhir");
  }

  /**
   * Add HTTP Basic authentication.
   *
   * <p>The password is encoded from its char[] directly to bytes, never through a String, so it is
   * not interned in the String pool where it could not be cleared.
   */
  private void addBasicAuth(HttpRequest.Builder builder, String username, char[] password) {
    if (password == null || password.length == 0) {
      log.warn("Password is null or empty, skipping Basic auth");
      return;
    }
    byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
    byte[] colonBytes = ":".getBytes(StandardCharsets.UTF_8);
    byte[] passwordBytes;
    try {
      CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE);
      ByteBuffer byteBuffer = encoder.encode(CharBuffer.wrap(password));
      passwordBytes = new byte[byteBuffer.remaining()];
      byteBuffer.get(passwordBytes);
    } catch (Exception e) {
      log.error("Failed to encode password bytes", e);
      return;
    }
    byte[] authBytes = new byte[usernameBytes.length + colonBytes.length + passwordBytes.length];
    System.arraycopy(usernameBytes, 0, authBytes, 0, usernameBytes.length);
    System.arraycopy(colonBytes, 0, authBytes, usernameBytes.length, colonBytes.length);
    System.arraycopy(passwordBytes, 0, authBytes, usernameBytes.length + colonBytes.length, passwordBytes.length);
    builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(authBytes));
    Arrays.fill(passwordBytes, (byte) 0);
    Arrays.fill(authBytes, (byte) 0);
  }
}
