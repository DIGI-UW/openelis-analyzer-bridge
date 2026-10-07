package org.itech.ahb.pairing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Which OpenELIS this Bridge is paired with, and whether it will accept a pairing now.
 *
 * <p>A pairing records the SHA-256 fingerprints of the certificate OpenELIS presents as a client and
 * of the one it serves, and is kept in the identity directory across restarts. Pairing is open only
 * while a code is pending: on an unpaired Bridge, the configured code or one generated at startup;
 * on a paired Bridge, a configured code different from the one that made the current pairing. Each
 * code pairs once, and repeated wrong codes close pairing until the next start.
 */
public class PairingState {

  public static final int MAX_FAILED_ATTEMPTS = 10;
  private static final String FILE = "pairing.json";
  private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
  private static final ObjectMapper JSON = new ObjectMapper();

  public enum Outcome {
    PAIRED,
    WRONG_CODE,
    CLOSED,
  }

  private record Pairing(String peer, String server, String codeHash, Instant pairedAt) {}

  private final Path file;
  private final String code;
  private final String displayedCode;
  private final boolean generated;
  private volatile Pairing pairing;
  private volatile int version;
  private int failedAttempts;
  private boolean consumed;

  public PairingState(Path directory, String configuredCode) {
    this.file = directory.resolve(FILE);
    this.pairing = load(file);
    String configured = normalize(configuredCode);
    if (pairing == null) {
      this.generated = configured.isEmpty();
      this.displayedCode = generated ? randomCode() : null;
      this.code = generated ? normalize(displayedCode) : configured;
    } else if (!configured.isEmpty() && !hash(configured).equals(pairing.codeHash())) {
      this.generated = false;
      this.displayedCode = null;
      this.code = configured;
    } else {
      this.generated = false;
      this.displayedCode = null;
      this.code = null;
    }
  }

  public boolean isPaired() {
    return pairing != null;
  }

  public synchronized boolean isOpen() {
    return code != null && !consumed && failedAttempts < MAX_FAILED_ATTEMPTS;
  }

  /** The code generated for an unpaired Bridge without a configured one, while it can still pair. */
  public Optional<String> generatedCode() {
    return generated && isOpen() ? Optional.of(displayedCode) : Optional.empty();
  }

  /** Changes each time a pairing is made, so clients built for an earlier pairing can be replaced. */
  public int version() {
    return version;
  }

  public synchronized Outcome pair(String attempt, String clientFingerprint, String serverFingerprint) {
    if (!isOpen()) {
      return Outcome.CLOSED;
    }
    if (!MessageDigest.isEqual(hash(normalize(attempt)).getBytes(), hash(code).getBytes())) {
      failedAttempts++;
      return Outcome.WRONG_CODE;
    }
    Pairing made = new Pairing(
      clientFingerprint.toLowerCase(Locale.ROOT),
      serverFingerprint.toLowerCase(Locale.ROOT),
      hash(code),
      Instant.now()
    );
    save(file, made);
    pairing = made;
    consumed = true;
    version++;
    return Outcome.PAIRED;
  }

  /** Whether a client certificate with this fingerprint is the paired OpenELIS. */
  public boolean isPeer(String fingerprint) {
    Pairing current = pairing;
    return current != null && fingerprint != null && current.peer().equalsIgnoreCase(fingerprint);
  }

  /** Whether a server certificate with this fingerprint is the paired OpenELIS. */
  public boolean trustsServer(String fingerprint) {
    Pairing current = pairing;
    return current != null && fingerprint != null && current.server().equalsIgnoreCase(fingerprint);
  }

  public Optional<Instant> pairedAt() {
    Pairing current = pairing;
    return current == null ? Optional.empty() : Optional.of(current.pairedAt());
  }

  private static String normalize(String code) {
    return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
  }

  private static String randomCode() {
    SecureRandom random = new SecureRandom();
    StringBuilder code = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      if (i > 0 && i % 4 == 0) code.append('-');
      code.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
    }
    return code.toString();
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Pairing load(Path file) {
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try {
      JsonNode stored = JSON.readTree(file.toFile());
      return new Pairing(
        stored.path("peerCertificateSha256").asText(),
        stored.path("serverCertificateSha256").asText(),
        stored.path("codeSha256").asText(),
        Instant.parse(stored.path("pairedAt").asText())
      );
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("Cannot read the pairing record " + file + "; remove it to pair again", e);
    }
  }

  private static void save(Path file, Pairing pairing) {
    ObjectNode stored = JSON.createObjectNode()
      .put("peerCertificateSha256", pairing.peer())
      .put("serverCertificateSha256", pairing.server())
      .put("codeSha256", pairing.codeHash())
      .put("pairedAt", pairing.pairedAt().toString());
    try {
      Files.createDirectories(file.getParent());
      Path temporary = Files.createTempFile(file.getParent(), FILE, ".tmp");
      Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(stored));
      try {
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot record the pairing in " + file, e);
    }
  }
}
