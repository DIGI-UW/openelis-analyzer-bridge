package org.itech.ahb.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;

/**
 * A GeneXpert profile written to the baseline contract, as far as the tests need: the HIV-1 viral
 * load assay with the records Cepheid documents for it (301-2002 Rev E 6.3.4.1.6, 303-0251 Rev A
 * 2.1.1) and one qualitative assay.
 */
public final class BaselineProfileFixtures {

  private static final String ASTM_PROFILE = "analyzer-profiles/genexpert-astm-v7.json";

  private BaselineProfileFixtures() {}

  public static ObjectNode genexpertHivViralLoad(ObjectMapper objectMapper) throws Exception {
    ObjectNode profile;
    try (InputStream input = BaselineProfileFixtures.class.getClassLoader().getResourceAsStream(ASTM_PROFILE)) {
      assertThat(input).as(ASTM_PROFILE).isNotNull();
      profile = (ObjectNode) objectMapper.readTree(input);
    }
    profile.put("schemaVersion", "2.0");
    ObjectNode extraction = profile.withObject("configDefaults").withObject("extractionOverrides");
    extraction.putObject("resultRecordSelection").put("mode", "ALL");
    extraction
      .putObject("resultParts")
      .put("testCode", "R.3.4")
      .put("assayName", "R.3.5")
      .put("assayVersion", "R.3.6")
      .put("analyte", "R.3.7")
      .put("complement", "R.3.8")
      .put("call", "R.4.1")
      .put("number", "R.4.2")
      .put("unit", "R.5")
      .put("range", "R.6")
      .put("flag", "R.7")
      .put("status", "R.9")
      .put("operator", "R.11")
      .put("started", "R.12")
      .put("completed", "R.13")
      .put("instrument", "R.14")
      .put("note", "C.4")
      .put("patientId", "P.5")
      .put("patientName", "P.6")
      .put("specimenDescriptor", "O.16");
    profile.withObject("configDefaults").putObject("numberFormat").put("decimalSeparator", ".");
    profile.set("default_test_mappings", objectMapper.readTree(TESTS));
    return profile;
  }

  private static final String TESTS =
    """
    [
      {
        "test_code":"HIVVL",
        "loinc":"20447-9",
        "unit":"copies/mL",
        "result_type":"quantitative",
        "values":["DETECTED","NOT DETECTED","INVALID"],
        "value_codes":{
          "DETECTED":[{"system":"http://loinc.org","code":"LA11882-0"},
                      {"system":"http://snomed.info/sct","code":"260373001"}],
          "NOT DETECTED":[{"system":"http://loinc.org","code":"LA11883-8"}],
          "INVALID":[{"system":"http://loinc.org","code":"LA15841-2"}]
        },
        "translations":{"DETECTED":["DÉTECTÉ"],"NOT DETECTED":["NON DÉTECTÉ"],"INVALID":["NON VALIDE"]},
        "call_component":"call",
        "run_failure_values":["ERROR","NO RESULT"],
        "components":[
          {"code":"call","label":"Call","result_type":"qualitative"},
          {"code":"LOG","label":"Log copies/mL","result_type":"quantitative","unit":"log copies/mL","sub_identity":"&LOG"},
          {"code":"HIV-1","label":"HIV-1","result_type":"qualitative","sub_identity":"HIV-1",
           "values":["POS","NEG","INVALID"],"run_failure_values":["NO RESULT"],
           "value_codes":{"POS":[{"system":"http://loinc.org","code":"LA6576-8"}],
                          "NEG":[{"system":"http://loinc.org","code":"LA6577-6"}],
                          "INVALID":[{"system":"http://loinc.org","code":"LA15841-2"}]}},
          {"code":"HIV-1-Ct","label":"HIV-1 Ct","result_type":"quantitative","sub_identity":"HIV-1&Ct"},
          {"code":"HIV-1-EndPt","label":"HIV-1 EndPt","result_type":"quantitative","sub_identity":"HIV-1&EndPt"},
          {"code":"HIV-1-DeltaCt","label":"HIV-1 Delta Ct","result_type":"quantitative","sub_identity":"HIV-1&Delta Ct"},
          {"code":"IQS-H","label":"IQS-H","result_type":"qualitative","sub_identity":"IQS-H",
           "values":["PASS","FAIL"],"run_failure_values":["NO RESULT"]},
          {"code":"IQS-H-Ct","label":"IQS-H Ct","result_type":"quantitative","sub_identity":"IQS-H&Ct"},
          {"code":"IQS-H-EndPt","label":"IQS-H EndPt","result_type":"quantitative","sub_identity":"IQS-H&EndPt"},
          {"code":"IQS-H-DeltaCt","label":"IQS-H Delta Ct","result_type":"quantitative","sub_identity":"IQS-H&Delta Ct"},
          {"code":"IQS-L","label":"IQS-L","result_type":"qualitative","sub_identity":"IQS-L",
           "values":["PASS","FAIL"],"run_failure_values":["NO RESULT"]},
          {"code":"IQS-L-Ct","label":"IQS-L Ct","result_type":"quantitative","sub_identity":"IQS-L&Ct"},
          {"code":"IQS-L-EndPt","label":"IQS-L EndPt","result_type":"quantitative","sub_identity":"IQS-L&EndPt"},
          {"code":"IQS-L-DeltaCt","label":"IQS-L Delta Ct","result_type":"quantitative","sub_identity":"IQS-L&Delta Ct"}
        ],
        "assay":{"name":"Xpert HIV-1 Viral Load XC","version":"3"},
        "source":{"document":"Cepheid 303-0251 Rev. A","section":"1, 2.1.1"}
      },
      {
        "test_code":"RIF",
        "loinc":"89372-7",
        "result_type":"qualitative",
        "values":["DETECTED","NOT DETECTED","INDETERMINATE"],
        "value_codes":{"DETECTED":[{"system":"http://loinc.org","code":"LA11882-0"}]},
        "assay":{"name":"Xpert MTB/RIF Ultra","version":"1"},
        "source":{"document":"Cepheid 301-2002 Rev. E","section":"6.3.4.1.6"}
      }
    ]
    """;
}
