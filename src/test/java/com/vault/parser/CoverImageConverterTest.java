package com.vault.parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import parser.CoverImageConverter;

class CoverImageConverterTest {

  @TempDir Path tempDir;

  @Test
  void aFileGivesTheSameCoverAsItsBytes() throws IOException {
    Path poster = tempDir.resolve("poster.png");
    BufferedImage image = new BufferedImage(640, 960, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 640; x++) {
      for (int y = 0; y < 960; y++) {
        image.setRGB(x, y, (x * 7 % 256) << 16 | (y * 3 % 256) << 8 | (x + y) % 256);
      }
    }
    ImageIO.write(image, "png", poster.toFile());

    CoverImageConverter.convert(poster, tempDir.resolve("from-file"));
    CoverImageConverter.convert(Files.readAllBytes(poster), tempDir.resolve("from-bytes"));

    byte[] cover = Files.readAllBytes(tempDir.resolve("from-file"));
    assertTrue(cover.length > 0);
    assertArrayEquals(Files.readAllBytes(tempDir.resolve("from-bytes")), cover);
  }

  @Test
  void aGifFileIsKeptAsItIs() throws IOException {
    Path poster = tempDir.resolve("poster.gif");
    byte[] gif = "GIF89a, the rest is never decoded".getBytes(StandardCharsets.US_ASCII);
    Files.write(poster, gif);

    CoverImageConverter.convert(poster, tempDir.resolve("cover"));

    assertArrayEquals(gif, Files.readAllBytes(tempDir.resolve("cover")));
  }

  @Test
  void aFileThatIsNotAnImageIsRejected() throws IOException {
    Path poster = tempDir.resolve("poster.jpg");
    Files.writeString(poster, "not an image");

    assertThrows(
        IOException.class, () -> CoverImageConverter.convert(poster, tempDir.resolve("cover")));
  }
}
