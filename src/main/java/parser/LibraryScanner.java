package parser;

import static util.PathUtils.getFileExtension;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import model.ParsedMedia;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class LibraryScanner {

  private static final Logger LOGGER = LogManager.getLogger(LibraryScanner.class);

  private static final Set<String> VIDEO_EXTENSIONS =
      Set.of("m4v", "avi", "mpg", "mp4", "mkv", "f4v", "wmv", "rmvb", "iso");
  private static final Set<String> IMAGE_EXTENSIONS =
      Set.of("jpg", "jpeg", "png", "bmp", "gif", "webp", "avif");

  private static final int SCAN_PARALLELISM =
      Math.clamp(Runtime.getRuntime().availableProcessors(), 2, 4);

  private LibraryScanner() {}

  public static List<ParsedMedia> scan(final Path root, final Set<String> skipFolders)
      throws IOException {
    final var visitor = new MediaFileVisitor(root, skipFolders);
    Files.walkFileTree(root, visitor);
    try (var pool = Executors.newFixedThreadPool(SCAN_PARALLELISM)) {
      final var futures =
          visitor.mediaFiles().stream()
              .flatMap(
                  m ->
                      m.nfoFiles.stream()
                          .map(
                              nfo ->
                                  pool.submit(() -> NfoParser.parse(nfo, m.images, m.mediaFiles))))
              .toList();

      return futures.stream()
          .map(
              f -> {
                try {
                  return f.get();
                } catch (Exception e) {
                  LOGGER.warn("Fail to parse nfo file.", e);
                  return Optional.<ParsedMedia>empty();
                }
              })
          .flatMap(Optional::stream)
          .toList();
    }
  }

  private static class MediaFileVisitor extends SimpleFileVisitor<Path> {
    private final Path root;
    private final Set<String> skipFolders;
    private final List<MediaFiles> mediaFiles;

    MediaFileVisitor(final Path root, final Set<String> skipFolders) {
      this.root = root;
      this.skipFolders = skipFolders;
      this.mediaFiles = new ArrayList<>();
    }

    public List<MediaFiles> mediaFiles() {
      return mediaFiles;
    }

    @Override
    public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs)
        throws IOException {
      final String name = dir.getFileName().toString();
      if (attrs.isSymbolicLink() || name.startsWith(".") || skipFolders.contains(name)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      final List<Path> media = new ArrayList<>();
      final List<Path> images = new ArrayList<>();
      final List<Path> nfoFiles = new ArrayList<>();
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
        for (Path p : entries) {
          if (!Files.isRegularFile(p) || Files.isHidden(p)) {
            continue;
          }
          final String fileName = p.getFileName().toString();
          final Optional<String> fileExtension = getFileExtension(p);
          if (fileExtension.isEmpty()) {
            LOGGER.warn("Fail to get extension for {}", fileName);
            continue;
          }
          final var ext = fileExtension.get();
          if (ext.equals("nfo")) {
            nfoFiles.add(p);
          } else if (VIDEO_EXTENSIONS.contains(ext)) {
            media.add(root.relativize(p));
          } else if (IMAGE_EXTENSIONS.contains(ext)
              && (fileName.contains("poster") || fileName.contains("thumb"))) {
            images.add(root.relativize(p));
          }
        }
      }

      if (!nfoFiles.isEmpty()) {
        mediaFiles.add(new MediaFiles(nfoFiles, media, images));
      }

      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(final Path file, final IOException e) {
      LOGGER.error("Failed to read {}: {}", file, e.getMessage());
      return FileVisitResult.CONTINUE;
    }
  }

  /**
   * Inner container for files interesting within current directory
   *
   * @param nfoFiles list of nfo files, for movie and TV shows folder, this should have size of 1,
   *     in episodes folder, this should the size of episodes nfo files.
   * @param mediaFiles
   * @param images
   */
  private record MediaFiles(List<Path> nfoFiles, List<Path> mediaFiles, List<Path> images) {}
}
