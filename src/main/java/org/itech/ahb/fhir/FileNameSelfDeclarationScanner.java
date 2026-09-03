package org.itech.ahb.fhir;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.itech.ahb.profile.TabularResultValueSelection;
import org.springframework.stereotype.Component;

/**
 * Scans a result file's free-text content columns for mentions of test codes
 * from a known mapping set, returning the file's self-declared identity.
 * Acceptance is strict: exactly one mapped code mentioned yields
 * {@link ScanResult.SelfDeclared}, zero or multiple mentions yield
 * {@link ScanResult.NoDeclaration} or {@link ScanResult.Ambiguous}
 * respectively, and files that can't be opened yield
 * {@link ScanResult.NotInterpretable}.
 */
@Component
@Slf4j
public class FileNameSelfDeclarationScanner {

    /** Sealed result of a scan — callers must exhaustively handle each case. */
    public sealed interface ScanResult {
        record SelfDeclared(String testCode) implements ScanResult {}
        record Ambiguous(Set<String> codes) implements ScanResult {}
        record NoDeclaration() implements ScanResult {}
        record NotInterpretable(String reason) implements ScanResult {}
    }

    public ScanResult scan(Path filePath, Map<String, String> columnMappings,
            TabularResultValueSelection resultSelection, Set<String> mappedTestCodes,
            Map<String, List<String>> scannerSynonyms) {

        if (filePath == null || !Files.exists(filePath)) {
            return new ScanResult.NotInterpretable("file does not exist: " + filePath);
        }
        if (mappedTestCodes == null || mappedTestCodes.isEmpty()) {
            return new ScanResult.NotInterpretable(
                    "analyzer has no configured test mappings — scanner needs at least one");
        }
        if (resultSelection == null) {
            return new ScanResult.NotInterpretable(
                    "analyzer has no result-value selection from its pinned profile");
        }

        // ODS branch — POI's WorkbookFactory doesn't handle OpenDocument
        // spreadsheets. Read via the zero-dep ZIP+XML path in FileResultParser
        // and run the same synonym-scan against the resulting row grid.
        String name = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".ods")) {
            return scanOdsFile(
                    filePath, columnMappings, resultSelection, mappedTestCodes,
                    scannerSynonyms);
        }

        try (InputStream in = new FileInputStream(filePath.toFile());
                Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = resolveSheet(workbook);
            if (sheet == null) {
                return new ScanResult.NotInterpretable("empty workbook or missing sheet");
            }

            DataFormatter formatter = new DataFormatter();

            Set<String> requiredContentHeaders = new LinkedHashSet<>();
            for (Map.Entry<String, String> mapping : columnMappings.entrySet()) {
                String semanticField = mapping.getValue();
                if (resultSelection.semanticFields().contains(semanticField)) {
                    requiredContentHeaders.add(mapping.getKey());
                }
            }

            if (requiredContentHeaders.isEmpty()) {
                return new ScanResult.NotInterpretable(
                        "profile has no selected result column mapping for scanner to read");
            }

            // Only AT LEAST ONE content header needs to be present — profiles
            // may map multiple columns to the same semantic role, and some
            // files omit optional ones.
            int headerRowIndex = findHeaderRowWithAny(sheet, formatter, requiredContentHeaders);
            if (headerRowIndex < 0) {
                return new ScanResult.NotInterpretable(
                        "no header row with any of the content columns " + requiredContentHeaders
                                + " found in first 200 rows");
            }

            Row headerRow = sheet.getRow(headerRowIndex);
            Map<String, Integer> headerIndex = new java.util.HashMap<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String cell = formatter.formatCellValue(headerRow.getCell(c));
                if (cell != null && !cell.isBlank()) {
                    headerIndex.put(cell.trim(), c);
                }
            }

            Set<Integer> contentColumnIndices = new LinkedHashSet<>();
            for (String header : requiredContentHeaders) {
                Integer colIdx = headerIndex.get(header);
                if (colIdx != null) {
                    contentColumnIndices.add(colIdx);
                }
            }

            if (contentColumnIndices.isEmpty()) {
                return new ScanResult.NotInterpretable(
                        "header row found but none of the expected content columns "
                                + requiredContentHeaders + " were in it");
            }

            Map<String, String> synonymToCode = buildSynonymMap(mappedTestCodes, scannerSynonyms);

            Set<String> foundCodes = new LinkedHashSet<>();
            for (int rowIdx = headerRowIndex + 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) continue;
                for (Integer colIdx : contentColumnIndices) {
                    String cell = formatter.formatCellValue(row.getCell(colIdx));
                    if (cell == null || cell.isBlank()) continue;
                    // Longest-match-wins per cell: iterate synonyms in
                    // length-descending order and blank out matched
                    // substrings so shorter prefixes can't double-count.
                    StringBuilder needle = new StringBuilder(cell.toLowerCase(Locale.ROOT));
                    for (Map.Entry<String, String> syn : synonymToCode.entrySet()) {
                        String synLower = syn.getKey();
                        int idx;
                        boolean matched = false;
                        while ((idx = needle.indexOf(synLower)) >= 0) {
                            matched = true;
                            for (int k = 0; k < synLower.length(); k++) {
                                needle.setCharAt(idx + k, ' ');
                            }
                        }
                        if (matched) {
                            foundCodes.add(syn.getValue());
                        }
                    }
                }
            }

            if (foundCodes.isEmpty()) {
                log.info("FileNameSelfDeclarationScanner: no mapped test codes found in {} "
                        + "(mappedTestCodes={}, synonyms={})", filePath.getFileName(),
                        mappedTestCodes, synonymToCode.keySet());
                return new ScanResult.NoDeclaration();
            }
            if (foundCodes.size() > 1) {
                log.info("FileNameSelfDeclarationScanner: multiple test codes {} found in {} — ambiguous",
                        foundCodes, filePath.getFileName());
                return new ScanResult.Ambiguous(Collections.unmodifiableSet(foundCodes));
            }
            String declared = foundCodes.iterator().next();
            log.info("FileNameSelfDeclarationScanner: {} self-declares as {}", filePath.getFileName(), declared);
            return new ScanResult.SelfDeclared(declared);

        } catch (IOException e) {
            log.warn("FileNameSelfDeclarationScanner: I/O error reading {}: {}", filePath, e.getMessage());
            return new ScanResult.NotInterpretable("I/O error: " + e.getMessage());
        } catch (Exception e) {
            log.warn("FileNameSelfDeclarationScanner: unexpected error reading {}: {}", filePath, e.getMessage(), e);
            return new ScanResult.NotInterpretable("unexpected error: " + e.getMessage());
        }
    }

    /**
     * ODS equivalent of the POI-based scan above. Reads content.xml via
     * {@link FileResultParser#readOdsContentXml(InputStream)} and runs the
     * same cell-text synonym-scan loop against the row grid.
     */
    private ScanResult scanOdsFile(Path filePath, Map<String, String> columnMappings,
            TabularResultValueSelection resultSelection, Set<String> mappedTestCodes,
            Map<String, List<String>> scannerSynonyms) {

        List<List<String>> rows;
        try (InputStream in = new FileInputStream(filePath.toFile())) {
            rows = FileResultParser.readOdsContentXml(in);
        } catch (IOException e) {
            log.warn("FileNameSelfDeclarationScanner: I/O error reading ODS {}: {}", filePath, e.getMessage());
            return new ScanResult.NotInterpretable("I/O error: " + e.getMessage());
        } catch (Exception e) {
            log.warn("FileNameSelfDeclarationScanner: failed to parse ODS {}: {}", filePath, e.getMessage(), e);
            return new ScanResult.NotInterpretable("ODS parse error: " + e.getMessage());
        }

        if (rows == null || rows.isEmpty()) {
            return new ScanResult.NotInterpretable("empty or missing content.xml table");
        }

        Set<String> requiredContentHeaders = new LinkedHashSet<>();
        for (Map.Entry<String, String> mapping : columnMappings.entrySet()) {
            String semanticField = mapping.getValue();
            if (resultSelection.semanticFields().contains(semanticField)) {
                requiredContentHeaders.add(mapping.getKey());
            }
        }

        if (requiredContentHeaders.isEmpty()) {
            return new ScanResult.NotInterpretable(
                    "profile has no selected result column mapping for scanner to read");
        }

        int headerRowIndex = findOdsHeaderRowWithAny(rows, requiredContentHeaders);
        if (headerRowIndex < 0) {
            return new ScanResult.NotInterpretable(
                    "no header row with any of the content columns " + requiredContentHeaders
                            + " found in first 200 ODS rows");
        }

        List<String> headerRow = rows.get(headerRowIndex);
        Map<String, Integer> headerIndex = new java.util.HashMap<>();
        for (int c = 0; c < headerRow.size(); c++) {
            String cell = headerRow.get(c);
            if (cell != null && !cell.isBlank()) {
                headerIndex.put(cell.trim(), c);
            }
        }

        Set<Integer> contentColumnIndices = new LinkedHashSet<>();
        for (String header : requiredContentHeaders) {
            Integer colIdx = headerIndex.get(header);
            if (colIdx != null) {
                contentColumnIndices.add(colIdx);
            }
        }

        if (contentColumnIndices.isEmpty()) {
            return new ScanResult.NotInterpretable(
                    "header row found but none of the expected content columns "
                            + requiredContentHeaders + " were in it");
        }

        Map<String, String> synonymToCode = buildSynonymMap(mappedTestCodes, scannerSynonyms);

        Set<String> foundCodes = new LinkedHashSet<>();
        for (int rowIdx = headerRowIndex + 1; rowIdx < rows.size(); rowIdx++) {
            List<String> row = rows.get(rowIdx);
            for (Integer colIdx : contentColumnIndices) {
                if (colIdx >= row.size()) continue;
                String cell = row.get(colIdx);
                if (cell == null || cell.isBlank()) continue;
                StringBuilder needle = new StringBuilder(cell.toLowerCase(Locale.ROOT));
                for (Map.Entry<String, String> syn : synonymToCode.entrySet()) {
                    String synLower = syn.getKey();
                    int idx;
                    boolean matched = false;
                    while ((idx = needle.indexOf(synLower)) >= 0) {
                        matched = true;
                        for (int k = 0; k < synLower.length(); k++) {
                            needle.setCharAt(idx + k, ' ');
                        }
                    }
                    if (matched) {
                        foundCodes.add(syn.getValue());
                    }
                }
            }
        }

        if (foundCodes.isEmpty()) {
            log.info("FileNameSelfDeclarationScanner: no mapped test codes found in ODS {} "
                    + "(mappedTestCodes={}, synonyms={})", filePath.getFileName(),
                    mappedTestCodes, synonymToCode.keySet());
            return new ScanResult.NoDeclaration();
        }
        if (foundCodes.size() > 1) {
            log.info("FileNameSelfDeclarationScanner: multiple test codes {} found in ODS {} — ambiguous",
                    foundCodes, filePath.getFileName());
            return new ScanResult.Ambiguous(Collections.unmodifiableSet(foundCodes));
        }
        String declared = foundCodes.iterator().next();
        log.info("FileNameSelfDeclarationScanner: ODS {} self-declares as {}", filePath.getFileName(), declared);
        return new ScanResult.SelfDeclared(declared);
    }

    private int findOdsHeaderRowWithAny(List<List<String>> rows, Set<String> headerCandidates) {
        int maxScan = Math.min(rows.size() - 1, 200);
        for (int r = 0; r <= maxScan; r++) {
            List<String> row = rows.get(r);
            for (String cell : row) {
                if (cell != null && headerCandidates.contains(cell.trim())) {
                    return r;
                }
            }
        }
        return -1;
    }

    /**
     * Build a lowercased-synonym → OE-test-code map sorted by length
     * descending, so longer synonyms are checked before shorter ones that
     * are their prefixes (e.g. "VIH-1" before "VIH").
     */
    private Map<String, String> buildSynonymMap(Set<String> mappedTestCodes,
            Map<String, List<String>> scannerSynonyms) {
        java.util.TreeMap<String, String> map = new java.util.TreeMap<>((a, b) -> {
            int byLen = Integer.compare(b.length(), a.length());
            return byLen != 0 ? byLen : a.compareTo(b);
        });
        for (String code : mappedTestCodes) {
            map.put(code.toLowerCase(Locale.ROOT), code);
            if (scannerSynonyms != null) {
                List<String> synonyms = scannerSynonyms.get(code);
                if (synonyms != null) {
                    for (String syn : synonyms) {
                        if (syn != null && !syn.isBlank()) {
                            map.put(syn.toLowerCase(Locale.ROOT), code);
                        }
                    }
                }
            }
        }
        return map;
    }

    private Sheet resolveSheet(Workbook workbook) {
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            if (sheet.getLastRowNum() > 0) {
                return sheet;
            }
        }
        return workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
    }

    /** First row containing at least one of the given header candidates, scanning up to 200 rows. */
    private int findHeaderRowWithAny(Sheet sheet, DataFormatter formatter, Set<String> headerCandidates) {
        int maxScan = Math.min(sheet.getLastRowNum(), 200);
        for (int r = 0; r <= maxScan; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                String cell = formatter.formatCellValue(row.getCell(c));
                if (cell != null && headerCandidates.contains(cell.trim())) {
                    return r;
                }
            }
        }
        return -1;
    }
}
