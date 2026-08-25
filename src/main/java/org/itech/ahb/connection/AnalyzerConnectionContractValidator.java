package org.itech.ahb.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;

final class AnalyzerConnectionContractValidator {

  private static final String CONTRACT_ROOT = "contracts/analyzer/v1/";

  private final JsonSchema createSchema;
  private final JsonSchema updateSchema;
  private final JsonSchema responseSchema;
  private final JsonSchema probeRequestSchema;
  private final JsonSchema probeResultSchema;
  private final JsonSchema runtimeCommandSchema;
  private final JsonSchema runtimeAcknowledgementSchema;

  AnalyzerConnectionContractValidator(ObjectMapper objectMapper) {
    createSchema = load(objectMapper, "connection-create.schema.json");
    updateSchema = load(objectMapper, "connection-update.schema.json");
    responseSchema = load(objectMapper, "analyzer-connection.schema.json");
    probeRequestSchema = load(objectMapper, "connection-probe-request.schema.json");
    probeResultSchema = load(objectMapper, "connection-probe-result.schema.json");
    runtimeCommandSchema = load(objectMapper, "connection-runtime-command.schema.json");
    runtimeAcknowledgementSchema = load(objectMapper, "connection-runtime-ack.schema.json");
  }

  void validateCreate(JsonNode request) {
    validate(createSchema, request, "Connection create");
  }

  void validateUpdate(JsonNode request) {
    validate(updateSchema, request, "Connection update");
  }

  void validateResponse(JsonNode response) {
    validate(responseSchema, response, "Connection response");
  }

  void validateProbeRequest(JsonNode request) {
    validate(probeRequestSchema, request, "Connection probe request");
  }

  void validateProbeResult(JsonNode result) {
    validate(probeResultSchema, result, "Connection probe result");
  }

  void validateRuntimeCommand(JsonNode command) {
    validate(runtimeCommandSchema, command, "Connection runtime command");
  }

  void validateRuntimeAcknowledgement(JsonNode acknowledgement) {
    validate(runtimeAcknowledgementSchema, acknowledgement, "Connection runtime acknowledgement");
  }

  private static JsonSchema load(ObjectMapper objectMapper, String name) {
    String resource = CONTRACT_ROOT + name;
    try (InputStream input = AnalyzerConnectionContractValidator.class.getClassLoader().getResourceAsStream(resource)) {
      if (input == null) {
        throw new AnalyzerConnectionException("Analyzer connection schema is not available: " + resource);
      }
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(objectMapper.readTree(input));
    } catch (IOException exception) {
      throw new AnalyzerConnectionException("Cannot load analyzer connection schema " + resource, exception);
    }
  }

  private static void validate(JsonSchema schema, JsonNode value, String description) {
    List<String> failures = schema
      .validate(value)
      .stream()
      .map(ValidationMessage::getMessage)
      .sorted(Comparator.naturalOrder())
      .toList();
    if (!failures.isEmpty()) {
      throw new AnalyzerConnectionException(description + " violates contract: " + String.join("; ", failures));
    }
  }
}
