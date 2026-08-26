package parser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import util.BundledLibrary;

class ImageCodecTest {

  private static final int MAX_WIDTH = 320;
  private static final int MAX_HEIGHT = 320;
  private static final int QUALITY = 80;

  private static final double MEAN_DIFF_TOLERANCE = 3;

  private static ImageCodec codec;
  private static byte[] expected;
  private static BufferedImage expectedImage;

  @BeforeAll
  static void setUp() throws IOException {
    codec = new ImageCodec(BundledLibrary.extract("/libimage"));
    expected = data("expected.jpg");
    expectedImage = ImageIO.read(new ByteArrayInputStream(expected));
  }

  @Test
  void verifyJpegMatchesExpected() throws IOException {
    final byte[] out = codec.toJpeg(data("source.jpg"), MAX_WIDTH, MAX_HEIGHT, QUALITY);
    assertArrayEquals(expected, out);
  }

  @ParameterizedTest
  @ValueSource(strings = {"source.png", "source.webp", "source-avif.avif", "source-mif1.avif"})
  void verifyAllFormats(final String name) throws IOException {
    final byte[] out = codec.toJpeg(data(name), MAX_WIDTH, MAX_HEIGHT, QUALITY);
    final BufferedImage image = ImageIO.read(new ByteArrayInputStream(out));
    assertEquals(expectedImage.getWidth(), image.getWidth());
    assertEquals(expectedImage.getHeight(), image.getHeight());
    final double diff = meanChannelDifference(expectedImage, image);
    assertTrue(diff < MEAN_DIFF_TOLERANCE);
  }

  @Test
  void verifyRejectUndecodableInput() {
    final byte[] garbage = new byte[512];
    for (int i = 0; i < garbage.length; i++) {
      garbage[i] = (byte) (i * 31);
    }
    assertThrows(
        IllegalStateException.class, () -> codec.toJpeg(garbage, MAX_WIDTH, MAX_HEIGHT, QUALITY));
  }

  private static byte[] data(final String name) throws IOException {
    try (InputStream in = ImageCodecTest.class.getResourceAsStream("/data/" + name)) {
      assertNotNull(in, "missing test resource " + name);
      return in.readAllBytes();
    }
  }

  /** Average absolute per-channel difference; catches channel swaps and structural corruption. */
  private static double meanChannelDifference(final BufferedImage a, final BufferedImage b) {
    long total = 0;
    for (int y = 0; y < a.getHeight(); y++) {
      for (int x = 0; x < a.getWidth(); x++) {
        final int pa = a.getRGB(x, y);
        final int pb = b.getRGB(x, y);
        total += Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
        total += Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF));
        total += Math.abs((pa & 0xFF) - (pb & 0xFF));
      }
    }
    return total / (a.getWidth() * a.getHeight() * 3.0);
  }
}
