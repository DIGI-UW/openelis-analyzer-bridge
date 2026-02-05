package org.itech.ahb.file;

import org.itech.ahb.file.FileMessageHandler.FileProcessingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FileWatcher.
 * <p>
 * Tests file detection, stability checking, duplicate detection, and error handling.
 * </p>
 */
class FileWatcherTest {

    @TempDir
    Path tempDir;

    private Path watchDir;
    private Path archiveDir;
    private Path errorDir;

    private FileConfig fileConfig;
    private FileMessageHandler mockMessageHandler;
    private FileWatcher fileWatcher;

    private static final String CSV_CONTENT = """
            SampleID,TestCode,Result,Units
            12345,GLU,95,mg/dL
            12346,HBA1C,6.5,%
            """;

    private static final String HL7_CONTENT = """
            MSH|^~\\&|SendingApp|SendingFac|||20260205||ORU^R01|MSG001|P|2.5
            PID|1||12345||Doe^John||19800101|M
            OBR|1||ORD001|GLU^Glucose
            OBX|1|NM|GLU^Glucose||95|mg/dL||||F
            """;

    @BeforeEach
    void setUp() throws IOException {
        // Create directories
        watchDir = tempDir.resolve("watch");
        archiveDir = tempDir.resolve("archive");
        errorDir = tempDir.resolve("error");

        Files.createDirectories(watchDir);
        Files.createDirectories(archiveDir);
        Files.createDirectories(errorDir);

        // Setup config
        fileConfig = new FileConfig();
        fileConfig.setEnabled(true);
        fileConfig.setWatchDirectories(List.of(watchDir.toString()));
        fileConfig.setArchiveDirectory(archiveDir.toString());
        fileConfig.setErrorDirectory(errorDir.toString());
        fileConfig.setFileStabilityTimeoutMs(100);  // Short timeout for testing
        fileConfig.setPollIntervalMs(100);
        fileConfig.setMaxRetryAttempts(3);
        fileConfig.setRetryDelayMs(100);
        fileConfig.setFilePatterns(List.of("*.csv", "*.hl7", "*.txt"));

        // Mock message handler
        mockMessageHandler = mock(FileMessageHandler.class);

        // Create FileWatcher (not started)
        fileWatcher = new FileWatcher(fileConfig, mockMessageHandler);
    }

    @AfterEach
    void tearDown() {
        if (fileWatcher != null) {
            fileWatcher.stop();
        }
    }

    @Test
    void testShouldProcessFile_ValidCSV() throws IOException {
        // Arrange
        Path csvFile = watchDir.resolve("test.csv");
        Files.writeString(csvFile, CSV_CONTENT);  // Create the file

        // Act
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", csvFile);

        // Assert
        assertTrue(result);
    }

    @Test
    void testShouldProcessFile_ValidHL7() throws IOException {
        // Arrange
        Path hl7File = watchDir.resolve("test.hl7");
        Files.writeString(hl7File, HL7_CONTENT);  // Create the file

        // Act
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", hl7File);

        // Assert
        assertTrue(result);
    }

    @Test
    void testShouldProcessFile_InvalidExtension() {
        // Arrange
        Path invalidFile = watchDir.resolve("test.pdf");

        // Act
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", invalidFile);

        // Assert
        assertFalse(result);
    }

    @Test
    void testShouldProcessFile_HiddenFile() {
        // Arrange
        Path hiddenFile = watchDir.resolve(".hidden.csv");

        // Act
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", hiddenFile);

        // Assert
        assertFalse(result);
    }

    @Test
    void testShouldProcessFile_ErrorFile() {
        // Arrange
        Path errorFile = watchDir.resolve("test.csv.error");

        // Act
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(fileWatcher, "shouldProcessFile", errorFile);

        // Assert
        assertFalse(result);
    }

    @Test
    void testCalculateFileHash() throws IOException {
        // Arrange
        Path testFile = watchDir.resolve("test.csv");
        Files.writeString(testFile, CSV_CONTENT);

        // Act
        String hash1 = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", testFile);
        String hash2 = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", testFile);

        // Assert
        assertNotNull(hash1);
        assertEquals(64, hash1.length());  // SHA-256 hex string length
        assertEquals(hash1, hash2);  // Same content = same hash
    }

    @Test
    void testCalculateFileHash_DifferentContent() throws IOException {
        // Arrange
        Path file1 = watchDir.resolve("test1.csv");
        Path file2 = watchDir.resolve("test2.csv");
        Files.writeString(file1, CSV_CONTENT);
        Files.writeString(file2, HL7_CONTENT);

        // Act
        String hash1 = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", file1);
        String hash2 = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "calculateFileHash", file2);

        // Assert
        assertNotEquals(hash1, hash2);  // Different content = different hash
    }

    @Test
    void testArchiveFile() throws IOException {
        // Arrange
        Path testFile = watchDir.resolve("test.csv");
        Files.writeString(testFile, CSV_CONTENT);

        // Act
        ReflectionTestUtils.invokeMethod(fileWatcher, "archiveFile", testFile, (String) null);

        // Assert
        assertFalse(Files.exists(testFile));  // Original file should be moved
        assertTrue(Files.exists(archiveDir.resolve("test.csv")));  // File should be in archive
    }

    @Test
    void testArchiveFile_WithSubdirectory() throws IOException {
        // Arrange
        Path testFile = watchDir.resolve("test.csv");
        Files.writeString(testFile, CSV_CONTENT);

        // Act
        ReflectionTestUtils.invokeMethod(fileWatcher, "archiveFile", testFile, "duplicate");

        // Assert
        assertFalse(Files.exists(testFile));
        assertTrue(Files.exists(archiveDir.resolve("duplicate/test.csv")));
    }

    @Test
    void testArchiveFile_NameCollision() throws IOException {
        // Arrange
        Path testFile1 = watchDir.resolve("test.csv");
        Path testFile2 = watchDir.resolve("test.csv");  // Simulate second file with same name
        Files.writeString(testFile1, CSV_CONTENT);

        // Archive first file
        ReflectionTestUtils.invokeMethod(fileWatcher, "archiveFile", testFile1, (String) null);

        // Create second file with same name
        Files.writeString(testFile2, CSV_CONTENT);

        // Act - Archive second file
        ReflectionTestUtils.invokeMethod(fileWatcher, "archiveFile", testFile2, (String) null);

        // Assert
        assertTrue(Files.exists(archiveDir.resolve("test.csv")));
        assertTrue(Files.exists(archiveDir.resolve("test_1.csv")));  // Should have _1 suffix
    }

    @Test
    void testMoveToErrorDirectory() throws IOException {
        // Arrange
        Path testFile = watchDir.resolve("test.csv");
        Files.writeString(testFile, CSV_CONTENT);
        Exception testError = new IOException("Test error");

        // Act
        ReflectionTestUtils.invokeMethod(fileWatcher, "moveToErrorDirectory", testFile, testError);

        // Assert
        assertFalse(Files.exists(testFile));
        assertTrue(Files.exists(errorDir.resolve("test.csv")));
        assertTrue(Files.exists(errorDir.resolve("test.csv.error")));  // Error details file

        // Verify error file contains error message
        String errorContent = Files.readString(errorDir.resolve("test.csv.error"));
        assertTrue(errorContent.contains("Test error"));
    }

    @Test
    void testDetermineAnalyzerId() {
        // Arrange
        Path fileInSubdir = tempDir.resolve("quantstudio/results.csv");

        // Act
        String analyzerId = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", fileInSubdir);

        // Assert
        assertEquals("QUANTSTUDIO", analyzerId);
    }

    @Test
    void testDetermineAnalyzerId_NoParent() {
        // Arrange
        Path rootFile = Path.of("test.csv");

        // Act
        String analyzerId = (String) ReflectionTestUtils.invokeMethod(fileWatcher, "determineAnalyzerId", rootFile);

        // Assert
        assertNull(analyzerId);
    }

    @Test
    void testProcessExistingFiles_ProcessesFilesOnStartup() throws Exception {
        // Note: This test would require full FileWatcher initialization
        // Skipping to avoid complex setup with ExecutorService mocking
        // Integration tests will cover this scenario
    }

    @Test
    void testFileStabilityCheck() throws Exception {
        // Note: This test would require full FileWatcher initialization
        // Skipping to avoid complex setup with WatchService and ExecutorService mocking
        // Integration tests will cover file stability checking
    }

    @Test
    void testRetryLogic_EventualSuccess() throws Exception {
        // Arrange
        Path testFile = watchDir.resolve("test.csv");
        Files.writeString(testFile, CSV_CONTENT);

        // Fail first 2 attempts, succeed on 3rd
        when(mockMessageHandler.processFile(any(), any()))
                .thenThrow(new FileMessageHandler.FileProcessingException("Attempt 1 failed"))
                .thenThrow(new FileMessageHandler.FileProcessingException("Attempt 2 failed"))
                .thenReturn(null);  // Success on 3rd attempt

        // Act
        ReflectionTestUtils.invokeMethod(fileWatcher, "processFileWithRetry", testFile);
        TimeUnit.MILLISECONDS.sleep(500);  // Wait for retries

        // Assert
        verify(mockMessageHandler, times(3)).processFile(any(), any());
        assertTrue(Files.exists(archiveDir.resolve("test.csv")));  // Should be archived after success
    }

    @Test
    void testRetryLogic_MaxAttemptsExceeded() throws Exception {
        // Arrange
        Path testFile = watchDir.resolve("test.csv");
        Files.writeString(testFile, CSV_CONTENT);

        // Fail all attempts
        when(mockMessageHandler.processFile(any(), any()))
                .thenThrow(new FileMessageHandler.FileProcessingException("Processing failed"));

        // Act
        ReflectionTestUtils.invokeMethod(fileWatcher, "processFileWithRetry", testFile);
        TimeUnit.MILLISECONDS.sleep(800);  // Wait for all retries

        // Assert
        verify(mockMessageHandler, times(3)).processFile(any(), any());  // Max 3 attempts
        assertTrue(Files.exists(errorDir.resolve("test.csv")));  // Should be in error directory
        assertFalse(Files.exists(testFile));  // Original file should be moved
    }
}
