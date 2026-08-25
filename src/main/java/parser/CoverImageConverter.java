package parser;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class CoverImageConverter {
  private static final Logger LOGGER = LogManager.getLogger(CoverImageConverter.class);

  private static final int MAX_WIDTH = 320;
  private static final int MAX_HEIGHT = 480;
  private static final float JPEG_QUALITY = 0.8f;

  private CoverImageConverter() {}

  public static void convert(final Path source, final Path dest) throws IOException {
    final String format = formatName(source);
    if (format == null) {
      LOGGER.warn("Skipping unsupported image: {}", source);
      return;
    }

    if ("gif".equalsIgnoreCase(format)) {
      Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
      return;
    }

    BufferedImage input = ImageIO.read(source.toFile());
    if (input == null) {
      throw new IOException("Unsupported image format: " + source);
    }

    Path tmp = dest.resolveSibling(dest.getFileName() + ".jpg");
    try {
      writeJpeg(scale(input), tmp);
      Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      Files.deleteIfExists(tmp);
      throw e;
    }
  }

  private static void writeJpeg(BufferedImage image, Path file) throws IOException {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) {
      throw new IOException("JPEG writer unavailable");
    }

    ImageWriter writer = writers.next();
    try (ImageOutputStream out = ImageIO.createImageOutputStream(file.toFile())) {
      writer.setOutput(out);
      ImageWriteParam params = writer.getDefaultWriteParam();
      if (params.canWriteCompressed()) {
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(JPEG_QUALITY);
      }
      writer.write(null, new IIOImage(image, null, null), params);
    } finally {
      writer.dispose();
    }
  }

  private static String formatName(Path path) throws IOException {
    try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
      if (in == null) {
        return null;
      }
      final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      return readers.hasNext() ? readers.next().getFormatName() : null;
    }
  }

  /** Aspect preserve scaling */
  private static BufferedImage scale(BufferedImage input) {
    Size size = targetSize(input.getWidth(), input.getHeight());
    BufferedImage out = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.drawImage(input, 0, 0, size.width(), size.height(), null);
    g.dispose();
    return out;
  }

  private static Size targetSize(int width, int height) {
    if (width <= 0 || height <= 0) {
      return new Size(MAX_WIDTH, MAX_HEIGHT);
    }
    double ratio = width / (double) height;
    double targetRatio = MAX_WIDTH / (double) MAX_HEIGHT;
    if (ratio > targetRatio) {
      return new Size(MAX_WIDTH, even(MAX_WIDTH / ratio));
    }
    return new Size(even(MAX_HEIGHT * ratio), MAX_HEIGHT);
  }

  private static int even(double value) {
    return Math.max(2, (int) Math.round(value / 2.0) * 2);
  }

  private record Size(int width, int height) {}
}
