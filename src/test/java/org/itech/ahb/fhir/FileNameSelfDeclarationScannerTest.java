package org.itech.ahb.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.itech.ahb.fhir.FileNameSelfDeclarationScanner.ScanResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link FileNameSelfDeclarationScanner}. Covers the four
 * ScanResult states (SelfDeclared / Ambiguous / NoDeclaration /
 * NotInterpretable) plus the critical real-file case: HIV-result.xlsx
 * with Fluorocycler mapping set + scanner_synonyms self-declares as
 * VIH-1 via the "HIV-1" / "GENERIC_HIV_CV" synonym table.
 */
class FileNameSelfDeclarationScannerTest {

    private final FileNameSelfDeclarationScanner scanner = new FileNameSelfDeclarationScanner();

    private static final Map<String, String> FLUOROCYCLER_MAPPING = Map.of(
            "Sample ID", "sampleId",
            "Type", "qcTask",
            "Calc. Conc.", "result",
            "Result", "interpretation");

    private static final Set<String> FLUOROCYCLER_MAPPED_CODES = Set.of("VIH-1", "CHIKV", "DENV", "ZIKV");

    private static final Map<String, List<String>> FLUOROCYCLER_SYNONYMS = Map.of(
            "VIH-1", List.of("VIH-1", "HIV-1", "GENERIC_HIV_CV"),
            "CHIKV", List.of("CHIKV", "Chikungunya"),
            "DENV", List.of("DENV", "Dengue"),
            "ZIKV", List.of("ZIKV", "Zika"));

    @Nested
    @DisplayName("Real LA2M files")
    class RealFiles {

        @Test
        @DisplayName("HIV-result.xlsx → SelfDeclared(VIH-1) via HIV-1/GENERIC_HIV_CV synonyms")
        void hivResultFile_selfDeclaresAsVIH1() throws Exception {
            Path file = Path.of("../../docs/debug-local/mnt-snapshot/la2m/central/"
                    + "analyzers_results/Fluorocycler-XT/HIV-result.xlsx");
            if (!Files.exists(file)) {
                // Gitignored real file not present in CI — skip cleanly.
                return;
            }
            ScanResult result = scanner.scan(file, FLUOROCYCLER_MAPPING,
                    FLUOROCYCLER_MAPPED_CODES, FLUOROCYCLER_SYNONYMS);
            assertInstanceOf(ScanResult.SelfDeclared.class, result,
                    "Expected SelfDeclared(VIH-1) from HIV-result.xlsx; got " + result);
            assertEquals("VIH-1", ((ScanResult.SelfDeclared) result).testCode());
        }

        @Test
        @DisplayName("ARBOVIROSE.xlsx → NoDeclaration (positional multiplex with zero inline target names)")
        void arbovioroseFile_returnsNoDeclaration() throws Exception {
            Path file = Path.of("../../docs/debug-local/mnt-snapshot/la2m/central/"
                    + "analyzers_results/Fluorocycler-XT/ARBOVIROSE.xlsx");
            if (!Files.exists(file)) {
                return;
            }
            // Result column holds positional multiplex slots like
            // "Negative -Negative -Positive (CP=28.6)" — no target labels,
            // so the file carries no inline identity and must be rejected.
            ScanResult result = scanner.scan(file, FLUOROCYCLER_MAPPING,
                    FLUOROCYCLER_MAPPED_CODES, FLUOROCYCLER_SYNONYMS);
            assertInstanceOf(ScanResult.NoDeclaration.class, result,
                    "Expected NoDeclaration from ARBOVIROSE.xlsx; got " + result);
        }
    }

    @Nested
    @DisplayName("Synthetic fixtures")
    class Synthetic {

        @Test
        @DisplayName("Single HIV-1 row → SelfDeclared(VIH-1)")
        void singleHivRow_returnsSelfDeclared(@TempDir Path tmp) throws Exception {
            Path file = tmp.resolve("single-hiv.xlsx");
            writeFluoroFile(file, new String[][] {
                    {"A", "1", "PAT-001", "Unknown", "", "HIV-1 + (CP=30.2)"}
            });
            ScanResult result = scanner.scan(file, FLUOROCYCLER_MAPPING,
                    FLUOROCYCLER_MAPPED_CODES, FLUOROCYCLER_SYNONYMS);
            assertInstanceOf(ScanResult.SelfDeclared.class, result);
            assertEquals("VIH-1", ((ScanResult.SelfDeclared) result).testCode());
        }

        @Test
        @DisplayName("Single GENERIC_HIV_CV standard row → SelfDeclared(VIH-1) via synonym")
        void singleStandardRow_returnsSelfDeclaredViaSynonym(@TempDir Path tmp) throws Exception {
            Path file = tmp.resolve("single-standard.xlsx");
            writeFluoroFile(file, new String[][] {
                    {"A", "1", "STD 1E7", "Standard", "", "STD 1E7 GENERIC_HIV_CV positiveControl(s) failed"}
            });
            ScanResult result = scanner.scan(file, FLUOROCYCLER_MAPPING,
                    FLUOROCYCLER_MAPPED_CODES, FLUOROCYCLER_SYNONYMS);
            assertInstanceOf(ScanResult.SelfDeclared.class, result);
            assertEquals("VIH-1", ((ScanResult.SelfDeclared) result).testCode());
        }

        @Test
        @DisplayName("Mixed HIV-1 and CHIKV rows → Ambiguous")
        void mixedHivAndChikv_returnsAmbiguous(@TempDir Path tmp) throws Exception {
            Path file = tmp.resolve("mixed.xlsx");
            writeFluoroFile(file, new String[][] {
                    {"A", "1", "PAT-001", "Unknown", "", "HIV-1 + (CP=30.2)"},
                    {"A", "2", "PAT-002", "Unknown", "", "CHIKV positive"}
            });
            ScanResult result = scanner.scan(file, FLUOROCYCLER_MAPPING,
                    FLUOROCYCLER_MAPPED_CODES, FLUOROCYCLER_SYNONYMS);
            assertInstanceOf(ScanResult.Ambiguous.class, result);
            Set<String> codes = ((ScanResult.Ambiguous) result).codes();
            assertTrue(codes.contains("VIH-1"), "Expected VIH-1 in ambiguous codes: " + codes);
            assertTrue(codes.contains("CHIKV"), "Expected CHIKV in ambiguous codes: " + codes);
        }

        @Test
        @DisplayName("Row with no mapped test vocabulary → NoDeclaration")
        void noMappedCodes_returnsNoDeclaration(@TempDir Path tmp) throws Exception {
            Path file = tmp.resolve("no-declaration.xlsx");
            writeFluoroFile(file, new String[][] {
                    {"A", "1", "PAT-001", "Unknown", "", "Not interpretable"},
                    {"A", "2", "PAT-002", "Unknown", "", "Invalid"}
            });
            ScanResult result = scanner.scan(file, FLUOROCYCLER_MAPPING,
                    FLUOROCYCLER_MAPPED_CODES, FLUOROCYCLER_SYNONYMS);
            assertInstanceOf(ScanResult.NoDeclaration.class, result);
        }

        @Test
        @DisplayName("Non-existent file → NotInterpretable")
        void missingFile_returnsNotInterpretable() {
            ScanResult result = scanner.scan(Path.of("/tmp/does-not-exist.xlsx"),
                    FLUOROCYCLER_MAPPING, FLUOROCYCLER_MAPPED_CODES, FLUOROCYCLER_SYNONYMS);
            assertInstanceOf(ScanResult.NotInterpretable.class, result);
        }

        @Test
        @DisplayName("Empty mappedTestCodes → NotInterpretable (scanner has no vocabulary)")
        void emptyMappedCodes_returnsNotInterpretable(@TempDir Path tmp) throws Exception {
            Path file = tmp.resolve("empty-mapping.xlsx");
            writeFluoroFile(file, new String[][] {
                    {"A", "1", "PAT-001", "Unknown", "", "HIV-1 + (CP=30.2)"}
            });
            ScanResult result = scanner.scan(file, FLUOROCYCLER_MAPPING,
                    Set.of(), FLUOROCYCLER_SYNONYMS);
            assertInstanceOf(ScanResult.NotInterpretable.class, result);
        }

        @Test
        @DisplayName("Null scannerSynonyms → code itself is the only search token")
        void nullSynonyms_usesCodeItself(@TempDir Path tmp) throws Exception {
            // When scanner_synonyms isn't in the profile, every mapped code
            // should still be searchable by its own literal name. A file
            // that says "VIH-1 positive" should self-declare as VIH-1 even
            // without a synonym table.
            Path file = tmp.resolve("literal-only.xlsx");
            writeFluoroFile(file, new String[][] {
                    {"A", "1", "PAT-001", "Unknown", "", "VIH-1 positive"}
            });
            ScanResult result = scanner.scan(file, FLUOROCYCLER_MAPPING,
                    FLUOROCYCLER_MAPPED_CODES, null);
            assertInstanceOf(ScanResult.SelfDeclared.class, result);
            assertEquals("VIH-1", ((ScanResult.SelfDeclared) result).testCode());
        }

        @Test
        @DisplayName("Longest-match wins: 'VIH-1' matches before 'VIH' prefix")
        void longestMatchWins(@TempDir Path tmp) throws Exception {
            // If an analyzer had both "VIH" and "VIH-1" in its mapping set,
            // a row saying "VIH-1 + CP=30" should match VIH-1, not VIH.
            Path file = tmp.resolve("longest-match.xlsx");
            writeFluoroFile(file, new String[][] {
                    {"A", "1", "PAT-001", "Unknown", "", "VIH-1 + (CP=30.2)"}
            });
            Set<String> mappedCodes = Set.of("VIH", "VIH-1");
            ScanResult result = scanner.scan(file, FLUOROCYCLER_MAPPING,
                    mappedCodes, null);
            assertInstanceOf(ScanResult.SelfDeclared.class, result);
            assertEquals("VIH-1", ((ScanResult.SelfDeclared) result).testCode(),
                    "Longer synonym VIH-1 should win over prefix VIH");
        }
    }

    /**
     * Write a minimal Fluorocycler-shape XLSX with header row and given
     * data rows. Matches the real file layout: Row / Col / Sample ID /
     * Type / Calc. Conc. / Result.
     */
    private static void writeFluoroFile(Path target, String[][] dataRows) throws Exception {
        Workbook wb = new XSSFWorkbook();
        try {
            Sheet sheet = wb.createSheet("Sheet1");
            String[] headers = {"Row", "Col", "Sample ID", "Type", "Calc. Conc.", "Result"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < dataRows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < dataRows[r].length; c++) {
                    row.createCell(c).setCellValue(dataRows[r][c]);
                }
            }
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    FileOutputStream fos = new FileOutputStream(target.toFile())) {
                wb.write(baos);
                fos.write(baos.toByteArray());
            }
        } finally {
            wb.close();
        }
    }
}
