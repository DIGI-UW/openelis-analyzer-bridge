package org.itech.ahb.pairing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;

/**
 * The certificate and key this Bridge serves HTTPS with and presents to OpenELIS as a client.
 *
 * <p>A deployment may mount a keystore. Without one the Bridge generates a self-signed key pair in
 * its identity directory on first start and keeps it, so the fingerprint OpenELIS pinned at pairing
 * stays valid across restarts and upgrades.
 */
public final class BridgeIdentity {

  public static final String KEYSTORE_FILE = "bridge-identity.p12";
  public static final String PASSWORD_FILE = "bridge-identity.password";
  private static final String ALIAS = "bridge";

  public record Keystore(Path path, String type, char[] password) {}

  private final X509Certificate certificate;
  private final KeyManager[] keyManagers;

  private BridgeIdentity(X509Certificate certificate, KeyManager[] keyManagers) {
    this.certificate = certificate;
    this.keyManagers = keyManagers;
  }

  /** The keystore generated in {@code directory}, created on first use with keytool from this JVM. */
  public static Keystore ensureGenerated(Path directory) {
    Path keystore = directory.resolve(KEYSTORE_FILE);
    Path passwordFile = directory.resolve(PASSWORD_FILE);
    try {
      if (Files.isRegularFile(keystore) && Files.isRegularFile(passwordFile)) {
        return new Keystore(keystore, "PKCS12", Files.readString(passwordFile).trim().toCharArray());
      }
      Files.createDirectories(directory);
      Files.deleteIfExists(keystore);
      String password = HexFormat.of().formatHex(new SecureRandom().generateSeed(24));
      Path pending = directory.resolve(PASSWORD_FILE + ".tmp");
      Files.deleteIfExists(pending);
      Files.createFile(pending, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.writeString(pending, password, StandardCharsets.UTF_8);
      generate(keystore, password);
      Files.move(pending, passwordFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      return new Keystore(keystore, "PKCS12", password.toCharArray());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create the Bridge identity in " + directory, e);
    }
  }

  private static void generate(Path keystore, String password) throws IOException {
    Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
    ProcessBuilder builder = new ProcessBuilder(
      keytool.toString(),
      "-genkeypair",
      "-alias",
      ALIAS,
      "-keyalg",
      "EC",
      "-groupname",
      "secp256r1",
      "-sigalg",
      "SHA256withECDSA",
      "-validity",
      "36500",
      "-dname",
      "CN=openelis-analyzer-bridge",
      "-ext",
      "SAN=dns:localhost,ip:127.0.0.1",
      "-keystore",
      keystore.toString(),
      "-storetype",
      "PKCS12",
      "-storepass:env",
      "BRIDGE_IDENTITY_PASSWORD",
      "-keypass:env",
      "BRIDGE_IDENTITY_PASSWORD",
      "-noprompt"
    )
      .redirectErrorStream(true)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD);
    builder.environment().put("BRIDGE_IDENTITY_PASSWORD", password);
    Process process = builder.start();
    // The password reaches keytool through its environment, never its command line.
    process.getOutputStream().close();
    try {
      if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        throw new IOException("keytool could not generate the Bridge identity");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new IOException("Interrupted while generating the Bridge identity", e);
    }
  }

  public static BridgeIdentity load(Keystore keystore) {
    try (InputStream in = Files.newInputStream(keystore.path())) {
      KeyStore store = KeyStore.getInstance(keystore.type());
      store.load(in, keystore.password());
      X509Certificate certificate = null;
      Enumeration<String> aliases = store.aliases();
      while (aliases.hasMoreElements() && certificate == null) {
        String alias = aliases.nextElement();
        if (store.isKeyEntry(alias)) {
          Certificate entry = store.getCertificate(alias);
          if (entry instanceof X509Certificate x509) certificate = x509;
        }
      }
      if (certificate == null) {
        throw new IllegalStateException("Keystore " + keystore.path() + " holds no key entry");
      }
      KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      factory.init(store, keystore.password());
      return new BridgeIdentity(certificate, factory.getKeyManagers());
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the Bridge identity " + keystore.path(), e);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Cannot load the Bridge identity " + keystore.path(), e);
    }
  }

  public X509Certificate certificate() {
    return certificate;
  }

  /** Key managers that present this certificate as a TLS client. */
  public KeyManager[] keyManagers() {
    return keyManagers.clone();
  }

  public String fingerprint() {
    return fingerprint(certificate);
  }

  public String certificatePem() {
    try {
      return (
        "-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(certificate.getEncoded()) +
        "\n-----END CERTIFICATE-----\n"
      );
    } catch (CertificateEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** SHA-256 of the certificate's DER encoding, as lowercase hex. */
  public static String fingerprint(X509Certificate certificate) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
