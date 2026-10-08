package org.itech.ahb.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The directories FILE connections may watch, read and clean. A connection's directory is accepted
 * only when its canonical form, with links resolved, lies under one of these roots, so neither a
 * {@code ..} segment nor a link can reach the bridge's own configuration or state.
 */
public final class ImportRoots {

  private final List<Path> roots;

  public ImportRoots(List<String> roots) {
    this.roots = roots
      .stream()
      .filter(root -> root != null && !root.isBlank())
      .map(root -> Path.of(root.trim()))
      .toList();
    for (Path root : this.roots) {
      if (!root.isAbsolute()) {
        throw new IllegalArgumentException("bridge.file.import-roots entries must be absolute paths: " + root);
      }
    }
  }

  /**
   * The canonical form of {@code directory}, which need not exist yet.
   *
   * @throws IOException if it is relative, outside every root, or reached through a broken link
   */
  public Path require(Path directory) throws IOException {
    if (!directory.isAbsolute()) {
      throw new IOException("FILE directory must be an absolute path: " + directory);
    }
    Path candidate = canonical(directory);
    for (Path root : roots) {
      if (candidate.startsWith(canonical(root))) {
        return candidate;
      }
    }
    throw new IOException(
      "FILE directory " + directory + " is outside the import roots " + roots + " (bridge.file.import-roots)"
    );
  }

  public List<Path> roots() {
    return roots;
  }

  /** Resolves links through the deepest part of the path that exists and keeps the rest as written. */
  private static Path canonical(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Path existing = absolute;
    Deque<Path> missing = new ArrayDeque<>();
    while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      missing.push(existing.getFileName());
      existing = existing.getParent();
    }
    Path resolved = existing == null ? absolute.getRoot() : existing.toRealPath();
    for (Path name : missing) {
      resolved = resolved.resolve(name);
    }
    return resolved;
  }
}
