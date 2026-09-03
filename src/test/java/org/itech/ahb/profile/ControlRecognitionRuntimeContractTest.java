package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.itech.ahb.config.AnalyzerRegistryConfig.AnalyzerEntry;
import org.itech.ahb.fhir.ASTMResultParser;
import org.itech.ahb.fhir.FileResultParser;
import org.itech.ahb.fhir.HL7ResultParser;
import org.junit.jupiter.api.Test;

class ControlRecognitionRuntimeContractTest {

  @Test
  void everyProductionParserEntryPointRequiresExplicitRecognition() {
    for (Class<?> parser : List.of(
      ASTMResultParser.class,
      HL7ResultParser.class,
      FileResultParser.class
    )) {
      List<Method> parserEntryPoints = Arrays
        .stream(parser.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .filter(method -> Modifier.isStatic(method.getModifiers()))
        .filter(method -> method.getName().equals("parse") || method.getName().startsWith("parseRaw") ||
          method.getName().startsWith("parseCsv") || method.getName().startsWith("parseOds"))
        .toList();

      assertThat(parserEntryPoints).isNotEmpty();
      assertThat(parserEntryPoints)
        .allSatisfy(method ->
          assertThat(method.getParameterTypes())
            .as("%s.%s must require profile-owned recognition", parser.getSimpleName(), method.getName())
            .contains(ControlResultRecognition.class)
        );
    }
  }

  @Test
  void analyzerEntriesDoNotInventAnImplicitNoneMode() {
    assertThat(new AnalyzerEntry().getControlResultRecognition()).isNull();
  }
}
