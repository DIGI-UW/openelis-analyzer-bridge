package org.itech.ahb.profile;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Profile-owned precedence for choosing a reportable value from a tabular row. */
public record TabularResultValueSelection(List<String> semanticFields) {

  private static final Set<String> ALLOWED_FIELDS = Set.of("result", "ctValue", "interpretation");

  public TabularResultValueSelection {
    Objects.requireNonNull(semanticFields, "semanticFields");
    semanticFields = List.copyOf(semanticFields);
    if (semanticFields.isEmpty()) {
      throw new IllegalArgumentException("At least one tabular result semantic field is required");
    }
    if (!ALLOWED_FIELDS.containsAll(semanticFields)) {
      throw new IllegalArgumentException("Unsupported tabular result semantic field");
    }
    if (new LinkedHashSet<>(semanticFields).size() != semanticFields.size()) {
      throw new IllegalArgumentException("Tabular result semantic fields must be unique");
    }
  }

  public static TabularResultValueSelection resultOnly() {
    return new TabularResultValueSelection(List.of("result"));
  }

  public static TabularResultValueSelection fromProfile(JsonNode profile) {
    JsonNode configured = profile.path("result_value_order");
    if (!configured.isArray() || configured.isEmpty()) {
      return resultOnly();
    }
    List<String> fields = new java.util.ArrayList<>();
    configured.forEach(field -> fields.add(field.asText()));
    return new TabularResultValueSelection(fields);
  }

  public String select(Function<String, String> valueBySemanticField) {
    for (String field : semanticFields) {
      String value = valueBySemanticField.apply(field);
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }
}
