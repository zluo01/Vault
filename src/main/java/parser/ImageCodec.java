package parser;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

final class ImageCodec {

  /** Keeps the loaded library alive for the lifetime of this binding. */
  private final Arena arena = Arena.ofShared();

  private final MethodHandle coverToJpeg;
  private final MethodHandle coverFree;

  ImageCodec(final Path library) {
    final SymbolLookup lookup = SymbolLookup.libraryLookup(library, arena);
    final Linker linker = Linker.nativeLinker();
    // int cover_to_jpeg(const uint8_t* data, int len, int max_w, int max_h, int quality,
    //                   uint8_t** out, size_t* out_len);
    coverToJpeg =
        linker.downcallHandle(
            find(lookup, "cover_to_jpeg"),
            FunctionDescriptor.of(
                JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    // void cover_free(uint8_t* ptr);
    coverFree =
        linker.downcallHandle(find(lookup, "cover_free"), FunctionDescriptor.ofVoid(ADDRESS));
  }

  private static MemorySegment find(final SymbolLookup lookup, final String name) {
    return lookup
        .find(name)
        .orElseThrow(() -> new IllegalStateException("libimage is missing symbol " + name));
  }

  /** decode and resize poster aspect-fit to the given scale, then return as jpeg encoded buffer */
  byte[] toJpeg(final byte[] image, final int maxWidth, final int maxHeight, final int quality) {
    try (Arena call = Arena.ofConfined()) {
      final MemorySegment input = call.allocateFrom(JAVA_BYTE, image);
      final MemorySegment outPointer = call.allocate(ADDRESS);
      final MemorySegment outSize = call.allocate(JAVA_LONG);
      final int ok =
          (int)
              coverToJpeg.invokeExact(
                  input, image.length, maxWidth, maxHeight, quality, outPointer, outSize);
      if (ok == 0) {
        throw new IllegalStateException(
            "Image conversion failed (unsupported format or encoding error)");
      }
      final long size = outSize.get(JAVA_LONG, 0);
      final MemorySegment jpeg = outPointer.getAtIndex(ADDRESS, 0);
      try {
        return jpeg.reinterpret(size).toArray(JAVA_BYTE);
      } finally {
        coverFree.invokeExact(jpeg);
      }
    } catch (final Throwable t) {
      throw propagate(t);
    }
  }

  private static RuntimeException propagate(final Throwable t) {
    if (t instanceof RuntimeException runtimeException) {
      return runtimeException;
    }
    if (t instanceof Error error) {
      throw error;
    }
    return new IllegalStateException(t);
  }
}
