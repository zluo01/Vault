package parser;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import util.BundledLibrary;

public final class CoverImageConverter {

  private static final int MAX_WIDTH = 320;
  private static final int MAX_HEIGHT = 480;
  private static final int JPEG_QUALITY = 80;

  private CoverImageConverter() {}

  public static void convert(final Path source, final Path dest) throws IOException {
    try (Arena arena = Arena.ofConfined()) {
      final MemorySegment image = read(source, arena);
      if (isGif(image)) {
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        return;
      }
      write(toJpeg(image, dest), dest);
    }
  }

  public static void convert(final byte[] data, final Path dest) throws IOException {
    if (isGif(MemorySegment.ofArray(data))) {
      Files.write(dest, data);
      return;
    }
    try (Arena arena = Arena.ofConfined()) {
      write(toJpeg(arena.allocateFrom(JAVA_BYTE, data), dest), dest);
    }
  }

  /**
   * Directly load source image into native memory
   *
   * @param source source image path
   * @param arena memory arena
   * @return native memory image segments
   * @throws IOException exception
   */
  private static MemorySegment read(final Path source, final Arena arena) throws IOException {
    try (FileChannel channel = FileChannel.open(source)) {
      if (channel.size() > Integer.MAX_VALUE) {
        throw new IOException("Image too large: " + source);
      }
      final MemorySegment image = arena.allocate(channel.size());
      final ByteBuffer buffer = image.asByteBuffer();
      int read = 0;
      while (buffer.hasRemaining() && read >= 0) {
        read = channel.read(buffer);
      }
      return image.asSlice(0, buffer.position());
    }
  }

  private static byte[] toJpeg(final MemorySegment image, final Path dest) throws IOException {
    try {
      return Codec.CODEC.toJpeg(image, MAX_WIDTH, MAX_HEIGHT, JPEG_QUALITY);
    } catch (IllegalStateException e) {
      throw new IOException("Unsupported image format for " + dest.getFileName(), e);
    }
  }

  private static void write(final byte[] jpeg, final Path dest) throws IOException {
    final Path tmp = dest.resolveSibling(dest.getFileName() + ".jpg");
    try {
      Files.write(tmp, jpeg);
      Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      Files.deleteIfExists(tmp);
      throw e;
    }
  }

  private static boolean isGif(final MemorySegment image) {
    return image.byteSize() >= 4
        && image.get(JAVA_BYTE, 0) == 'G'
        && image.get(JAVA_BYTE, 1) == 'I'
        && image.get(JAVA_BYTE, 2) == 'F'
        && image.get(JAVA_BYTE, 3) == '8';
  }

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
}
