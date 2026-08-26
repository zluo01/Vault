package parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import model.Comic;
import model.ParsedMedia;
import util.PathUtils;

public final class ComicParser {

  private static final Set<String> PAGE_EXTENSIONS =
      Set.of("jpg", "jpeg", "png", "bmp", "webp", "avif");

  private ComicParser() {}

  public static ParsedMedia parse(final Path root, final Path relativeFile) throws IOException {
    final Path comicPath = root.resolve(relativeFile);
    final int pages;
    try (ZipFile zip = new ZipFile(comicPath.toFile())) {
      pages = (int) zip.stream().filter(ComicParser::isPage).count();
    }
    if (pages == 0) {
      throw new IOException("No image pages in " + comicPath);
    }
    final Comic comic =
        new Comic(
            PathUtils.getBaseName(comicPath),
            comicPath,
            comicPath.getFileName().toString(),
            PathUtils.stripComicExtensions(relativeFile),
            pages);
    return new ParsedMedia(comic, Map.of());
  }

  public static byte[] extractCover(final Path comicFile) throws IOException {
    try (ZipFile zip = new ZipFile(comicFile.toFile())) {
      final List<ZipEntry> pages =
          zip.stream()
              .map(ZipEntry.class::cast)
              .filter(ComicParser::isPage)
              .sorted(Comparator.comparing(ZipEntry::getName))
              .toList();
      if (pages.isEmpty()) {
        throw new IOException("No image pages in " + comicFile);
      }
      final ZipEntry cover =
          pages.stream()
              .filter(entry -> entryBaseName(entry).startsWith("cover"))
              .findFirst()
              .orElse(pages.getFirst());
      try (InputStream in = zip.getInputStream(cover)) {
        return in.readAllBytes();
      }
    }
  }

  private static boolean isPage(final ZipEntry entry) {
    if (entry.isDirectory()) {
      return false;
    }
    final String name = entry.getName().toLowerCase(Locale.ROOT);
    final int dot = name.lastIndexOf('.');
    return dot >= 0 && PAGE_EXTENSIONS.contains(name.substring(dot + 1));
  }

  private static String entryBaseName(final ZipEntry entry) {
    final String name = entry.getName().toLowerCase(Locale.ROOT);
    final int slash = name.lastIndexOf('/');
    return slash < 0 ? name : name.substring(slash + 1);
  }
}
