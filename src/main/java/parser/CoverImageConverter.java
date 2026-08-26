package parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import util.BundledLibrary;

public final class CoverImageConverter {

  private static final int MAX_WIDTH = 320;
  private static final int MAX_HEIGHT = 480;
  private static final int JPEG_QUALITY = 80;

  private CoverImageConverter() {}

  private static final class Codec {
    private static final ImageCodec CODEC = load();

    private static ImageCodec load() {
      try {
        return new ImageCodec(BundledLibrary.extract("/libimage"));
      } catch (final IOException e) {
        throw new IllegalStateException("Fail to load bundled libimage.", e);
      }
    }
  }

  public static void convert(final Path source, final Path dest) throws IOException {
    convert(Files.readAllBytes(source), dest);
  }

  public static void convert(final byte[] data, final Path dest) throws IOException {
    if (isGif(data)) {
      Files.write(dest, data);
      return;
    }

    final byte[] jpeg;
    try {
      jpeg = Codec.CODEC.toJpeg(data, MAX_WIDTH, MAX_HEIGHT, JPEG_QUALITY);
    } catch (IllegalStateException e) {
      throw new IOException("Unsupported image format for " + dest.getFileName(), e);
    }

    final Path tmp = dest.resolveSibling(dest.getFileName() + ".jpg");
    try {
      Files.write(tmp, jpeg);
      Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      Files.deleteIfExists(tmp);
      throw e;
    }
  }

  private static boolean isGif(final byte[] data) {
    return data.length >= 4 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8';
  }
}
