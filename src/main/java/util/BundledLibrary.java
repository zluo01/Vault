package util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class BundledLibrary {

  private BundledLibrary() {}

  /**
   * Extract native bundle library to a temporary file for loading
   *
   * @param resource path under the resources folder
   * @return temp file path
   * @throws IOException exception
   */
  public static Path extract(final String resource) throws IOException {
    try (InputStream in = BundledLibrary.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("Missing bundled library resource " + resource);
      }
      // .bin suffix prevents Windows from appending .dll to the binary
      final Path lib = Files.createTempFile("vault" + resource.replace('/', '-') + "-", ".bin");
      lib.toFile().deleteOnExit();
      Files.copy(in, lib, StandardCopyOption.REPLACE_EXISTING);
      return lib;
    }
  }
}
