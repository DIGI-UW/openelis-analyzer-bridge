package org.itech.ahb.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** Anyone who can write to a watched share can drop a file; its size must not decide the heap. */
class FileWatcherSizeLimitTest {

  private static final String ANALYZER = "file-owner";

  @TempDir
  Path directory;

  @Test
  void aFileLargerThanTheLimitIsParkedForAnOperatorWithoutBeingRead() throws Exception {
    Path file = Files.write(directory.resolve("huge.csv"), new byte[4096]);
    Path small = Files.writeString(directory.resolve("small.csv"), "Sample,Test,Result\nS1,T1,1\n");
    FileMessageHandler handler = mock(FileMessageHandler.class);
    FileConfig config = new FileConfig();
    config.setMaxFileSizeBytes(1024);
    SqliteFileStateStore store = new SqliteFileStateStore(directory.resolve("state.db"));
    try {
      FileWatcher watcher = new FileWatcher(config, handler, store);
      try {
        watcher.addWatchDirectory(directory, "*.csv", ANALYZER);

        ReflectionTestUtils.invokeMethod(watcher, "processFileWithRetry", file);
        ReflectionTestUtils.invokeMethod(watcher, "processFileWithRetry", small);

        verify(handler, never()).receiveBytes(eq(file), anyString(), isNull(), any(byte[].class));
        verify(handler, times(1)).receiveBytes(eq(small), eq(ANALYZER), isNull(), any(byte[].class));
        var parked = store.list(FileProcessingState.Status.FAILED_NEEDS_HANDLING, 10, 0);
        assertEquals(1, parked.size());
        assertTrue(parked.get(0).lastError().contains("1024"), parked.get(0).lastError());
      } finally {
        watcher.stop();
      }
    } finally {
      store.close();
    }
  }
}
