package org.itech.ahb.profile;

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

public class PortableProfileValidator {

  private static final String SCHEMA_RESOURCE = "/contracts/analyzer/v1/portable-profile.schema.json";

  private final JsonSchema schema;

  public PortableProfileValidator(ObjectMapper objectMapper) {
    try (InputStream input = PortableProfileValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
      if (input == null) {
        throw new ProfileCatalogException("Portable profile schema is not packaged at " + SCHEMA_RESOURCE);
      }
      JsonNode schemaDocument = objectMapper.readTree(input);
      schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaDocument);
    } catch (IOException exception) {
      throw new ProfileCatalogException("Cannot load portable profile schema", exception);
    }
  }

  public void validate(JsonNode profile) {
    Set<ValidationMessage> messages = schema.validate(profile);
    if (!messages.isEmpty()) {
      String details = messages.stream().map(ValidationMessage::getMessage).sorted().collect(Collectors.joining("; "));
      throw new ProfileCatalogException("Portable profile violates schema: " + details);
    }
  }
}
