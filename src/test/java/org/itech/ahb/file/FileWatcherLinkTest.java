package org.itech.ahb.file;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** A link inside a watched share must not lead the watcher to read outside the import roots. */
class FileWatcherLinkTest {

  @TempDir
  Path temp;

  private FileWatcher watcher(Path root, FileMessageHandler handler, SqliteFileStateStore store) {
    FileConfig config = new FileConfig();
    config.setImportRoots(List.of(root.toString()));
    return new FileWatcher(config, handler, store);
  }

  @Test
  void aLinkedFileThatLeadsOutsideTheRootsIsNotRead() throws Exception {
    Path root = Files.createDirectories(temp.resolve("imports"));
    Path share = Files.createDirectories(root.resolve("share"));
    Path secret = Files.writeString(Files.createDirectories(temp.resolve("bridge-state")).resolve("config.csv"), "a,b\n1,2\n");
    Path link = Files.createSymbolicLink(share.resolve("result.csv"), secret);
    FileMessageHandler handler = mock(FileMessageHandler.class);
    SqliteFileStateStore store = new SqliteFileStateStore(temp.resolve("state.db"));
    FileWatcher watcher = watcher(root, handler, store);
    try {
      watcher.addWatchDirectory(share, "*.csv", "analyzer");

      ReflectionTestUtils.invokeMethod(watcher, "processFileWithRetry", link);

      verify(handler, never()).receiveBytes(eq(link), anyString(), isNull(), any(byte[].class));
    } finally {
      watcher.stop();
      store.close();
    }
  }

  @Test
  void aWatchedDirectoryLinkRepointedOutsideTheRootsIsNotRead() throws Exception {
    Path root = Files.createDirectories(temp.resolve("imports"));
    Path inside = Files.createDirectories(root.resolve("real"));
    Path outside = Files.createDirectories(temp.resolve("bridge-state"));
    Files.writeString(outside.resolve("result.csv"), "a,b\n1,2\n");
    Path link = Files.createSymbolicLink(root.resolve("link"), inside);
    FileMessageHandler handler = mock(FileMessageHandler.class);
    SqliteFileStateStore store = new SqliteFileStateStore(temp.resolve("state.db"));
    FileWatcher watcher = watcher(root, handler, store);
    try {
      watcher.addWatchDirectory(link, "*.csv", "analyzer");
      Files.delete(link);
      Files.createSymbolicLink(root.resolve("link"), outside);

      ReflectionTestUtils.invokeMethod(watcher, "processFileWithRetry", link.resolve("result.csv"));

      verify(handler, never()).receiveBytes(any(Path.class), anyString(), isNull(), any(byte[].class));
    } finally {
      watcher.stop();
      store.close();
    }
  }
}
