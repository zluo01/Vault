package com.vault.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import enums.TagCategory;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import model.Movie;
import model.ParsedMedia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import parser.LibraryScanner;

class LibraryScannerTest {

  @TempDir Path tempDir;

  @Test
  void scansMovieWithPosterAndTags() throws IOException {
    Path library = tempDir.resolve("library");
    Path movieDir = library.resolve("John Wick");
    Files.createDirectories(movieDir);
    Files.writeString(
        movieDir.resolve("movie.nfo"),
        "<movie><title>John Wick</title><year>2014</year><genre>Action</genre></movie>");
    Files.writeString(movieDir.resolve("movie.mkv"), "video");
    writeJpeg(movieDir.resolve("poster.jpg"));

    List<ParsedMedia> items = LibraryScanner.scan(library, Set.of());

    assertEquals(1, items.size());
    ParsedMedia parsed = items.get(0);
    Movie movie = assertInstanceOf(Movie.class, parsed.media());
    assertEquals("John Wick", movie.title());
    assertEquals("2014", movie.year());
    assertEquals(movieDir, movie.path());
    assertEquals("movie.mkv", movie.file());
    assertEquals("poster.jpg", movie.mainPoster());
    assertEquals(List.of("Action"), parsed.tags(TagCategory.GENRE));
  }

  @Test
  void scansGifPoster() throws IOException {
    Path library = tempDir.resolve("library");
    Path movieDir = library.resolve("Animated Poster");
    Files.createDirectories(movieDir);
    Files.writeString(
        movieDir.resolve("movie.nfo"),
        "<movie><title>Animated Poster</title><year>2024</year></movie>");
    Files.writeString(movieDir.resolve("movie.mkv"), "video");
    writeGif(movieDir.resolve("poster.gif"));

    List<ParsedMedia> items = LibraryScanner.scan(library, Set.of());

    assertEquals(1, items.size());
    assertEquals("poster.gif", items.get(0).media().mainPoster());
  }

  @Test
  void ignoresNonImagePosterFiles() throws IOException {
    Path library = tempDir.resolve("library");
    Path movieDir = library.resolve("Unsupported Poster");
    Files.createDirectories(movieDir);
    Files.writeString(
        movieDir.resolve("movie.nfo"),
        "<movie><title>Unsupported Poster</title><year>2024</year></movie>");
    Files.writeString(movieDir.resolve("movie.mkv"), "video");
    Files.writeString(movieDir.resolve("poster.txt"), "not an image");

    List<ParsedMedia> items = LibraryScanner.scan(library, Set.of());

    assertEquals(1, items.size());
    assertNull(items.get(0).media().mainPoster());
  }

  @Test
  void skipsConfiguredFolders() throws IOException {
    Path library = tempDir.resolve("library");
    Path skipped = library.resolve("_Bonus").resolve("Extra");
    Files.createDirectories(skipped);
    Files.writeString(skipped.resolve("movie.nfo"), "<movie><title>Extra</title></movie>");
    Files.writeString(skipped.resolve("movie.mkv"), "video");

    assertEquals(1, LibraryScanner.scan(library, Set.of()).size());
    assertTrue(LibraryScanner.scan(library, Set.of("_Bonus")).isEmpty());
  }

  private static void writeJpeg(Path file) throws IOException {
    BufferedImage image = new BufferedImage(100, 150, BufferedImage.TYPE_INT_RGB);
    assumeTrue(ImageIO.write(image, "jpg", file.toFile()), "JPEG writer unavailable");
  }

  private static void writeGif(Path file) throws IOException {
    BufferedImage image = new BufferedImage(100, 150, BufferedImage.TYPE_INT_RGB);
    assumeTrue(ImageIO.write(image, "gif", file.toFile()), "GIF writer unavailable");
  }
}
