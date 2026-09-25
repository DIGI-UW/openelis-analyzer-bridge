package org.itech.ahb.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.fhir.FhirBundleBuilder;
import org.itech.ahb.fhir.FileResultParser;
import org.itech.ahb.fhir.HL7ResultParser;
import org.itech.ahb.outbox.FailureReason;
import org.itech.ahb.outbox.RenderedDelivery;
import org.itech.ahb.routing.NormalizedBundleRenderer.Outcome;

/** Renders retained FILE bytes using only the immutable receipt-time parser context. No I/O to the source path. */
public final class FileResultRenderer {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private FileResultRenderer() {}

  public static Outcome render(byte[] bytes, String contextJson, String targetUri) {
    try {
      FileReceiptContext context = MAPPER.readValue(contextJson, FileReceiptContext.class);
      if (context.columnMappings().isEmpty() || context.recognition() == null || context.resultSelection() == null) {
        return new Outcome.Failed(
          FailureReason.UNPINNED_PROFILE,
          "Retained FILE context is missing parser or recognition settings"
        );
      }
      List<HL7ResultParser.ParsedResults> parsed;
      try (var input = new ByteArrayInputStream(bytes)) {
        parsed = switch (context.extension()) {
          case ".csv", ".tsv", ".txt" -> FileResultParser.parseCsv(
            bytes,
            context.columnMappings(),
            context.delimiter(),
            context.skipRows(),
            context.selectedTestCode(),
            context.recognition(),
            context.layout(),
            context.resultSelection()
          );
          case ".xls", ".xlsx" -> FileResultParser.parse(
            input,
            context.columnMappings(),
            context.selectedTestCode(),
            context.recognition(),
            context.layout(),
            context.resultSelection()
          );
          case ".ods" -> FileResultParser.parseOds(
            input,
            context.columnMappings(),
            context.selectedTestCode(),
            context.recognition(),
            context.layout(),
            context.resultSelection()
          );
          default -> throw new IllegalArgumentException("Unsupported retained FILE extension: " + context.extension());
        };
      }
      if (parsed == null || parsed.isEmpty()) return new Outcome.Failed(
        FailureReason.PARSE_NO_RESULTS,
        "Retained FILE parse produced no results"
      );
      String hash = FileDeliveryIdentity.contentHash(bytes);
      AnalyzerEntry snapshot = new AnalyzerEntry();
      snapshot.setBridgeConnectionId(context.connectionId());
      snapshot.setId(context.analyzerId());
      snapshot.setName(context.analyzerName());
      snapshot.setProfileId(context.profileId());
      snapshot.setProfileRevision(context.profileRevision());
      snapshot.setControlResultRecognition(context.recognition());
      snapshot.setRecognitionFingerprint(context.recognitionFingerprint());
      snapshot.setCodeToLoinc(context.codeToLoinc());
      List<RenderedDelivery> deliveries = new ArrayList<>();
      for (var accession : parsed) {
        deliveries.add(
          new RenderedDelivery(
            FileDeliveryIdentity.forAccession(context.connectionId(), hash, accession.accessionNumber()),
            accession.accessionNumber(),
            buildFileFhirBundle(snapshot, accession, context.sourcePath(), hash),
            context.connectionId(),
            context.analyzerId(),
            context.profileId(),
            context.profileRevision(),
            targetUri
          )
        );
      }
      return new Outcome.Rendered(deliveries);
    } catch (Exception exception) {
      return new Outcome.Failed(
        FailureReason.RENDER_ERROR,
        "Retained FILE could not be parsed: " + exception.getClass().getSimpleName()
      );
    }
  }

  /** Build one normalized FILE result bundle from the pinned saved connection. */
  public static String buildFileFhirBundle(
    AnalyzerEntry analyzerEntry,
    HL7ResultParser.ParsedResults parsed,
    String sourceFile,
    String contentHash
  ) {
    Objects.requireNonNull(analyzerEntry, "analyzerEntry is required");
    java.util.function.Function<String, String> codeToLoinc = analyzerEntry::getLoincForCode;
    FhirBundleBuilder.AnalyzerContext context = new FhirBundleBuilder.AnalyzerContext(
      analyzerEntry.getBridgeConnectionId(),
      analyzerEntry.getId(),
      analyzerEntry.getProfileId(),
      analyzerEntry.getProfileRevision(),
      "FILE",
      "FILE",
      FhirBundleBuilder.DeviceInfo.fromSenderToken(sourceFile, analyzerEntry.getName()),
      analyzerEntry.getControlResultRecognition(),
      analyzerEntry.getRecognitionFingerprint()
    );
    return FhirBundleBuilder.buildNormalizedBundle(
      parsed.accessionNumber(),
      parsed.results(),
      context,
      codeToLoinc,
      FileDeliveryIdentity.forAccession(analyzerEntry.getBridgeConnectionId(), contentHash, parsed.accessionNumber())
    );
  }
}
