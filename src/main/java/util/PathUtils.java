package util;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

public final class PathUtils {
  private static final String[] IMAGE_EXTENSIONS = {
    ".jpg", ".png", ".jpeg", ".bmp", ".gif", ".webp", ".avif"
  };

  private PathUtils() {}

  public static String stripImageExtensions(String path) {
    return stripExtensions(path, IMAGE_EXTENSIONS);
  }

  public static String stripImageExtensions(Path path) {
    return stripImageExtensions(path.toString());
  }

  private static String stripExtensions(String path, String[] extensions) {
    String result = path.replace('\\', '/');
    String lower = result.toLowerCase(Locale.ROOT);
    for (String ext : extensions) {
      if (lower.endsWith(ext)) {
        return result.substring(0, result.length() - ext.length());
      }
    }
    return result;
  }

  public static Path resolveRelative(Path base, String relative) {
    Path result = base;
    for (String segment : relative.split("/")) {
      if (!segment.isEmpty()) {
        result = result.resolve(segment);
      }
    }
    return result;
  }

  public static String getBaseName(final Path path) {
    final String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot <= 0 ? name : name.substring(0, dot);
  }

  public static Optional<String> getFileExtension(final Path filePath) {
    final String name = filePath.getFileName().toString();
    final int lastIndexOf = name.lastIndexOf(".");
    if (lastIndexOf == -1) {
      return Optional.empty();
    }
    final var ext = name.substring(lastIndexOf + 1);
    if (ext.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(ext.toLowerCase(Locale.ROOT));
  }
}
