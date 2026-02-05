package org.itech.ahb.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CSVParser.
 */
@ExtendWith(MockitoExtension.class)
class CSVParserTest {

    @Mock
    private FileConfig fileConfig;

    private CSVParser csvParser;

    private static final String VALID_CSV_DEFAULT_MAPPING = """
            SampleID,TestCode,Result,Units,Timestamp,Flags
            12345,GLU,95,mg/dL,2026-02-05T10:00:00,N
            12346,HBA1C,6.5,%,2026-02-05T10:05:00,N
            12347,CHOL,180,mg/dL,2026-02-05T10:10:00,H
            """;

    private static final String VALID_CSV_CUSTOM_MAPPING = """
            Sample,Test,Value,Unit
            SAMP001,Glucose,95,mg/dL
            SAMP002,Hemoglobin,6.5,%
            """;

    private static final String VALID_CSV_WITH_HEADERS = """
            PatientID,TestName,ResultValue,Unit
            P001,Glucose,85,mg/dL
            P002,Cholesterol,200,mg/dL
            """;

    private static final String INVALID_CSV_EMPTY = "";

    private static final String INVALID_CSV_SINGLE_COLUMN = """
            SingleColumn
            Value1
            Value2
            """;

    @BeforeEach
    void setUp() {
        csvParser = new CSVParser(fileConfig);
    }

    @Test
    void testParseWithDefaultMapping() throws IOException {
        // Arrange
        when(fileConfig.hasCustomCsvMapping("TEST-ANALYZER")).thenReturn(false);

        // Act
        List<Map<String, String>> records = csvParser.parse(VALID_CSV_DEFAULT_MAPPING, "TEST-ANALYZER");

        // Assert
        assertNotNull(records);
        assertEquals(3, records.size());

        Map<String, String> firstRecord = records.get(0);
        assertEquals("12345", firstRecord.get(CSVParser.FIELD_SAMPLE_ID));
        assertEquals("GLU", firstRecord.get(CSVParser.FIELD_TEST_CODE));
        assertEquals("95", firstRecord.get(CSVParser.FIELD_RESULT));
        assertEquals("mg/dL", firstRecord.get(CSVParser.FIELD_UNITS));
        assertEquals("2026-02-05T10:00:00", firstRecord.get(CSVParser.FIELD_TIMESTAMP));
        assertEquals("N", firstRecord.get(CSVParser.FIELD_FLAGS));

        Map<String, String> thirdRecord = records.get(2);
        assertEquals("12347", thirdRecord.get(CSVParser.FIELD_SAMPLE_ID));
        assertEquals("H", thirdRecord.get(CSVParser.FIELD_FLAGS));
    }

    @Test
    void testParseWithCustomMapping() throws IOException {
        // Arrange
        Map<String, Integer> customMapping = new HashMap<>();
        customMapping.put(CSVParser.FIELD_SAMPLE_ID, 0);  // Sample
        customMapping.put(CSVParser.FIELD_TEST_CODE, 1);   // Test
        customMapping.put(CSVParser.FIELD_RESULT, 2);      // Value
        customMapping.put(CSVParser.FIELD_UNITS, 3);       // Unit

        when(fileConfig.hasCustomCsvMapping("QUANTSTUDIO-001")).thenReturn(true);
        when(fileConfig.getCsvMappingForAnalyzer("QUANTSTUDIO-001")).thenReturn(customMapping);

        // Act
        List<Map<String, String>> records = csvParser.parse(VALID_CSV_CUSTOM_MAPPING, "QUANTSTUDIO-001");

        // Assert
        assertNotNull(records);
        assertEquals(2, records.size());

        Map<String, String> firstRecord = records.get(0);
        assertEquals("SAMP001", firstRecord.get(CSVParser.FIELD_SAMPLE_ID));
        assertEquals("Glucose", firstRecord.get(CSVParser.FIELD_TEST_CODE));
        assertEquals("95", firstRecord.get(CSVParser.FIELD_RESULT));
        assertEquals("mg/dL", firstRecord.get(CSVParser.FIELD_UNITS));
    }

    @Test
    void testParseWithHeaders() throws IOException {
        // Act
        List<Map<String, String>> records = csvParser.parseWithHeaders(VALID_CSV_WITH_HEADERS);

        // Assert
        assertNotNull(records);
        assertEquals(2, records.size());

        Map<String, String> firstRecord = records.get(0);
        assertEquals("P001", firstRecord.get("patientid"));
        assertEquals("Glucose", firstRecord.get("testname"));
        assertEquals("85", firstRecord.get("resultvalue"));
        assertEquals("mg/dL", firstRecord.get("unit"));
    }

    @Test
    void testIsValidCSV_Valid() {
        // Act & Assert
        assertTrue(csvParser.isValidCSV(VALID_CSV_DEFAULT_MAPPING));
        assertTrue(csvParser.isValidCSV(VALID_CSV_WITH_HEADERS));
    }

    @Test
    void testIsValidCSV_Empty() {
        // Act & Assert
        assertFalse(csvParser.isValidCSV(INVALID_CSV_EMPTY));
        assertFalse(csvParser.isValidCSV("   "));
        assertFalse(csvParser.isValidCSV(null));
    }

    @Test
    void testIsValidCSV_SingleColumn() {
        // Act & Assert
        assertFalse(csvParser.isValidCSV(INVALID_CSV_SINGLE_COLUMN));
    }

    @Test
    void testIsValidCSV_OnlyHeader() {
        String onlyHeader = "SampleID,TestCode,Result\n";

        // Act & Assert
        assertFalse(csvParser.isValidCSV(onlyHeader));
    }

    @Test
    void testToCsvString() throws IOException {
        // Arrange
        when(fileConfig.hasCustomCsvMapping("TEST-ANALYZER")).thenReturn(false);
        List<Map<String, String>> records = csvParser.parse(VALID_CSV_DEFAULT_MAPPING, "TEST-ANALYZER");

        // Act
        String csvString = csvParser.toCsvString(records);

        // Assert
        assertNotNull(csvString);
        assertFalse(csvString.isEmpty());
        assertTrue(csvString.contains("sampleId"));
        assertTrue(csvString.contains("12345"));
        assertTrue(csvString.contains("GLU"));
    }

    @Test
    void testToCsvString_WithCommasInValues() throws IOException {
        // Arrange
        Map<String, String> record1 = new HashMap<>();
        record1.put("sampleId", "12345");
        record1.put("testCode", "TEST,CODE");  // Contains comma
        record1.put("result", "95");

        List<Map<String, String>> records = List.of(record1);

        // Act
        String csvString = csvParser.toCsvString(records);

        // Assert
        assertNotNull(csvString);
        assertTrue(csvString.contains("\"TEST,CODE\""));  // Should be quoted
    }

    @Test
    void testToCsvString_WithNewlinesInValues() throws IOException {
        // Arrange
        Map<String, String> record1 = new HashMap<>();
        record1.put("sampleId", "12345");
        record1.put("testCode", "TEST");
        record1.put("result", "Line1\nLine2");  // Contains newline

        List<Map<String, String>> records = List.of(record1);

        // Act
        String csvString = csvParser.toCsvString(records);

        // Assert
        assertNotNull(csvString);
        assertTrue(csvString.contains("\"Line1\nLine2\""));  // Should be quoted
    }

    @Test
    void testToCsvString_Empty() throws IOException {
        // Act
        String csvString = csvParser.toCsvString(List.of());

        // Assert
        assertEquals("", csvString);
    }

    @Test
    void testToCsvString_Null() throws IOException {
        // Act
        String csvString = csvParser.toCsvString(null);

        // Assert
        assertEquals("", csvString);
    }

    @Test
    void testParseSkipsEmptyLines() throws IOException {
        // Arrange
        String csvWithEmptyLines = """
                SampleID,TestCode,Result,Units,Timestamp,Flags
                12345,GLU,95,mg/dL,2026-02-05T10:00:00,N

                12346,HBA1C,6.5,%,2026-02-05T10:05:00,N

                """;
        when(fileConfig.hasCustomCsvMapping("TEST-ANALYZER")).thenReturn(false);

        // Act
        List<Map<String, String>> records = csvParser.parse(csvWithEmptyLines, "TEST-ANALYZER");

        // Assert
        assertEquals(2, records.size());  // Empty lines should be ignored
    }

    @Test
    void testParseTrimsWhitespace() throws IOException {
        // Arrange
        String csvWithWhitespace = """
                SampleID,TestCode,Result,Units
                  12345  ,  GLU  ,  95  ,  mg/dL
                12346,HBA1C,6.5,%
                """;
        when(fileConfig.hasCustomCsvMapping("TEST-ANALYZER")).thenReturn(false);

        // Act
        List<Map<String, String>> records = csvParser.parse(csvWithWhitespace, "TEST-ANALYZER");

        // Assert
        Map<String, String> firstRecord = records.get(0);
        assertEquals("12345", firstRecord.get(CSVParser.FIELD_SAMPLE_ID));
        assertEquals("GLU", firstRecord.get(CSVParser.FIELD_TEST_CODE));
        assertEquals("95", firstRecord.get(CSVParser.FIELD_RESULT));
    }

    @Test
    void testParseWithNullAnalyzerId() throws IOException {
        // Act - Should use default mapping when analyzer ID is null
        List<Map<String, String>> records = csvParser.parse(VALID_CSV_DEFAULT_MAPPING, null);

        // Assert
        assertNotNull(records);
        assertEquals(3, records.size());
    }
}
