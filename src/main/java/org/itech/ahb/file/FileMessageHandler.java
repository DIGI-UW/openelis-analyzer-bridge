package org.itech.ahb.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.fhir.HL7ResultParser;
import org.itech.ahb.model.Protocol;
import org.itech.ahb.model.Transport;
import org.itech.ahb.normalizer.MessageEnvelope;
import org.itech.ahb.outbox.OutboxDispatcher;
import org.itech.ahb.outbox.OutboxStore;
import org.itech.ahb.outbox.ReceivedFile;
import org.springframework.stereotype.Component;

/** Captures FILE input durably. The shared outbox owns parsing, delivery, retry and dead letters. */
@Component
public class FileMessageHandler {

  private final AnalyzerRuntimeRegistry registry;
  private final OutboxStore store;
  private final OutboxDispatcher dispatcher;
  private final ObjectMapper mapper;

  public FileMessageHandler(
    AnalyzerRuntimeRegistry registry,
    OutboxStore store,
    OutboxDispatcher dispatcher,
    ObjectMapper mapper
  ) {
    this.registry = registry;
    this.store = store;
    this.dispatcher = dispatcher;
    this.mapper = mapper;
  }

  @FunctionalInterface
  public interface ProgressCallback {
    void onAccession(int current, int total, String accessionNumber);
  }

  public MessageEnvelope processFile(Path path, String analyzerId) throws IOException, FileProcessingException {
    return processFile(path, analyzerId, null);
  }

  public MessageEnvelope processFile(Path path, String analyzerId, String perFileTestCode)
    throws IOException, FileProcessingException {
    return processFile(path, analyzerId, perFileTestCode, null);
  }

  /** Receipt is synchronous; per-accession delivery progress is available through the outbox API. */
  public MessageEnvelope processFile(Path path, String analyzerId, String perFileTestCode, ProgressCallback progress)
    throws IOException, FileProcessingException {
    return receiveBytes(path, analyzerId, perFileTestCode, Files.readAllBytes(path));
  }

  /** Uploads and watchers pass the same bytes they used for content identity; never reopen a mutable source. */
  public MessageEnvelope receiveBytes(Path path, String analyzerId, String perFileTestCode, byte[] bytes)
    throws IOException, FileProcessingException {
    try {
      var candidates = registry
        .getRegisteredAnalyzers()
        .values()
        .stream()
        .filter(
          entry -> analyzerId != null && analyzerId.equals(entry.getId()) && "FILE".equals(entry.getExpectedProtocol())
        )
        .toList();
      if (candidates.size() != 1) throw new FileProcessingException("A unique active FILE connection is required");
      var context = FileReceiptContext.capture(path, candidates.get(0), perFileTestCode);
      var receipt = store.receiveFile(
        new ReceivedFile(
          bytes,
          path.toString(),
          context.connectionId(),
          context.analyzerId(),
          context.profileId(),
          context.profileRevision(),
          mapper.writeValueAsString(context),
          context.interpretationHash(mapper)
        )
      );
      dispatcher.signal();
      return MessageEnvelope.builder()
        .protocol(Protocol.CSV)
        .transport(Transport.FILE)
        .sourceId(path.toString())
        .outboxReceiptId(receipt.id())
        .resolvedAnalyzerId(analyzerId)
        .build();
    } catch (RuntimeException exception) {
      throw new FileProcessingException("File was not accepted: " + exception.getMessage(), exception);
    }
  }

  static String buildFileFhirBundle(
    AnalyzerEntry entry,
    HL7ResultParser.ParsedResults parsed,
    String sourceFile,
    String hash
  ) {
    return FileResultRenderer.buildFileFhirBundle(entry, parsed, sourceFile, hash);
  }

  static String resolveFileTestCode(AnalyzerEntry entry, String explicit) {
    return explicit != null && !explicit.isBlank() ? explicit.trim() : entry == null ? null : entry.getFileTestCode();
  }

  public static class FileProcessingException extends Exception {

    public FileProcessingException(String message) {
      super(message);
    }

    public FileProcessingException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
