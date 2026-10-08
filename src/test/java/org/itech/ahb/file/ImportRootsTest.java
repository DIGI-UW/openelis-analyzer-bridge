package org.itech.ahb.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportRootsTest {

  @TempDir
  Path temp;

  @Test
  void aDirectoryUnderARootIsAcceptedInCanonicalForm() throws IOException {
    Path root = Files.createDirectories(temp.resolve("imports"));
    ImportRoots roots = new ImportRoots(List.of(root.toString()));

    assertEquals(root.toRealPath().resolve("genexpert/incoming"), roots.require(root.resolve("genexpert/./incoming")));
    assertEquals(root.toRealPath(), roots.require(root));
  }

  @Test
  void aDirectoryOutsideEveryRootIsRefused() throws IOException {
    Path root = Files.createDirectories(temp.resolve("imports"));
    Path other = Files.createDirectories(temp.resolve("drops"));
    ImportRoots roots = new ImportRoots(List.of(root.toString(), other.toString()));

    assertEquals(other.toRealPath(), roots.require(other));
    assertThrows(IOException.class, () -> roots.require(temp.resolve("data")));
    assertThrows(IOException.class, () -> roots.require(root.resolve("../outside")));
    assertThrows(IOException.class, () -> roots.require(Path.of("relative/imports")));
    assertThrows(IOException.class, () -> roots.require(temp.resolve("imports-elsewhere")));
  }

  @Test
  void aLinkInsideARootThatLeadsOutsideIsRefused() throws IOException {
    Path root = Files.createDirectories(temp.resolve("imports"));
    Path outside = Files.createDirectories(temp.resolve("bridge-state"));
    Files.createSymbolicLink(root.resolve("escape"), outside);
    ImportRoots roots = new ImportRoots(List.of(root.toString()));

    assertThrows(IOException.class, () -> roots.require(root.resolve("escape")));
    assertThrows(IOException.class, () -> roots.require(root.resolve("escape/not-yet-created")));
  }

  @Test
  void aWatcherRefusesToWatchADirectoryOutsideTheRoots() throws IOException {
    Path root = Files.createDirectories(temp.resolve("imports"));
    FileConfig config = new FileConfig();
    config.setImportRoots(List.of(root.toString()));
    FileWatcher watcher = new FileWatcher(config, null, null);
    try {
      assertThrows(IOException.class, () -> watcher.addWatchDirectory(temp.resolve("state"), "*", "analyzer"));
      watcher.addWatchDirectory(Files.createDirectories(root.resolve("genexpert")), "*", "analyzer");
    } finally {
      watcher.stop();
    }
  }
}
