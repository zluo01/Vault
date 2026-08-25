package com.vault.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import util.PathUtils;

class PathUtilsTest {

  @Test
  void stripImageExtensionsRemovesAllFormats() {
    assertEquals("poster", PathUtils.stripImageExtensions("poster.jpg"));
    assertEquals("poster", PathUtils.stripImageExtensions("poster.png"));
    assertEquals("poster", PathUtils.stripImageExtensions("poster.jpeg"));
    assertEquals("poster", PathUtils.stripImageExtensions("poster.bmp"));
    assertEquals("poster", PathUtils.stripImageExtensions("poster.gif"));
    assertEquals("poster", PathUtils.stripImageExtensions("poster.webp"));
    assertEquals("poster", PathUtils.stripImageExtensions("poster.avif"));
    assertEquals("Poster", PathUtils.stripImageExtensions("Poster.GIF"));
  }

  @Test
  void stripImageExtensionsNormalizesBackslashes() {
    assertEquals(
        "folder/subfolder/poster", PathUtils.stripImageExtensions("folder\\subfolder\\poster.jpg"));
  }

  @Test
  void stripImageExtensionsOnlyStripsSuffix() {
    assertEquals("gifted.jpg/poster", PathUtils.stripImageExtensions("gifted.jpg/poster.png"));
  }

  @Test
  void stripImageExtensionsNoExtension() {
    assertEquals("poster", PathUtils.stripImageExtensions("poster"));
  }

  @Test
  void resolveRelativeResolvesEachSegmentAndSkipsLeadingSlash() {
    Path base = Path.of("/covers/Movie");
    assertEquals(
        base.resolve("John Wick").resolve("poster"),
        PathUtils.resolveRelative(base, "John Wick/poster"));
    assertEquals(base.resolve("poster"), PathUtils.resolveRelative(base, "/poster"));
  }

  @Test
  void resolveRelativeProducesEncodedFileUri() {
    Path base = Path.of("/covers/Movie");
    String uri = PathUtils.resolveRelative(base, "John Wick/poster").toUri().toString();
    assertTrue(uri.startsWith("file:"), uri);
    assertTrue(uri.contains("/Movie/John%20Wick/poster"), uri);
  }
}
