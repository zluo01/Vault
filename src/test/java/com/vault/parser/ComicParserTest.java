package com.vault.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import model.Comic;
import model.ParsedMedia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import parser.ComicParser;

class ComicParserTest {

  @TempDir Path tempDir;

  @Test
  void verifyParse() throws IOException {
    Path comics = tempDir.resolve("series");
    Files.createDirectories(comics);
    writeCbz(
        comics.resolve("Volume 1.cbz"), entry("001.jpg", "page one"), entry("002.jpg", "page two"));

    ParsedMedia parsed = ComicParser.parse(tempDir, Path.of("series", "Volume 1.cbz"));
    Comic comic = (Comic) parsed.media();
    assertEquals("Volume 1", comic.title());
    assertEquals("Volume 1.cbz", comic.file());
    assertEquals(tempDir.resolve("series").resolve("Volume 1.cbz"), comic.path());
    assertEquals("series/Volume 1", comic.poster());
    assertEquals(2, comic.pages());
    assertTrue(parsed.tags().isEmpty());
  }

  @Test
  void verifyCoverIsFirstSortedPage() throws IOException {
    Path cbz = tempDir.resolve("a.cbz");
    // stored out of order with a metadata file; the sorted first image wins
    writeCbz(
        cbz,
        entry("ComicInfo.xml", "<xml/>"),
        entry("002.jpg", "second"),
        entry("001.jpg", "first"));
    assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), ComicParser.extractCover(cbz));
  }

  @Test
  void verifyCoverEntryPreferred() throws IOException {
    Path cbz = tempDir.resolve("b.cbz");
    writeCbz(cbz, entry("001.jpg", "page"), entry("cover.png", "the cover"));
    assertArrayEquals("the cover".getBytes(StandardCharsets.UTF_8), ComicParser.extractCover(cbz));
  }

  @Test
  void verifyRejectArchiveWithoutPages() throws IOException {
    Path cbz = tempDir.resolve("c.cbz");
    writeCbz(cbz, entry("ComicInfo.xml", "<xml/>"));
    assertThrows(IOException.class, () -> ComicParser.parse(tempDir, Path.of("c.cbz")));
    assertThrows(IOException.class, () -> ComicParser.extractCover(cbz));
  }

  private record Entry(String name, byte[] content) {}

  private static Entry entry(String name, String content) {
    return new Entry(name, content.getBytes(StandardCharsets.UTF_8));
  }

  private static void writeCbz(Path file, Entry... entries) throws IOException {
    try (OutputStream out = Files.newOutputStream(file);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Entry entry : entries) {
        zip.putNextEntry(new ZipEntry(entry.name()));
        zip.write(entry.content());
        zip.closeEntry();
      }
    }
  }
}
