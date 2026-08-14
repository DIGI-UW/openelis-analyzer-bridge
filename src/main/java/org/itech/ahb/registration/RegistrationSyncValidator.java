package org.itech.ahb.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

final class RegistrationSyncValidator {

  private static final String SCHEMA_RESOURCE = "/contracts/analyzer/v1/registration-sync.schema.json";

  private final JsonSchema schema;

  RegistrationSyncValidator(ObjectMapper objectMapper) {
    try (InputStream input = RegistrationSyncValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new RegistrationSyncException("Registration sync schema is not packaged at " + SCHEMA_RESOURCE);
      }
      schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(objectMapper.readTree(input));
    } catch (IOException exception) {
      throw new RegistrationSyncException("Cannot load registration sync schema", exception);
    }
  }

  void validate(JsonNode payload) {
    Set<ValidationMessage> messages = schema.validate(payload);
    if (!messages.isEmpty()) {
      String details = messages.stream().map(ValidationMessage::getMessage).sorted().collect(Collectors.joining("; "));
      throw new RegistrationSyncException("Registration sync violates schema: " + details);
    }
  }
}
