package service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record AppConfig(Path dataDir) {

  public static final Os OS = Os.detect();

  public static AppConfig resolveDefault() {
    final String home = System.getProperty("user.home");
    final Path base =
        switch (OS) {
          case WINDOWS -> {
            String appData = System.getenv("APPDATA");
            yield appData != null && !appData.isBlank()
                ? Path.of(appData)
                : Path.of(home, "AppData", "Roaming");
          }
          case MAC -> Path.of(home, "Library", "Application Support");
          case LINUX -> {
            String xdg = System.getenv("XDG_DATA_HOME");
            yield xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".local", "share");
          }
        };
    return new AppConfig(base.resolve("vault"));
  }

  public Path databaseFile() {
    return dataDir.resolve("sqlite.db");
  }

  public Path coversDir() {
    return dataDir.resolve("covers");
  }

  public void ensureCreated() throws IOException {
    Files.createDirectories(coversDir());
  }

  public enum Os {
    WINDOWS,
    MAC,
    LINUX;

    private static Os detect() {
      final String name = System.getProperty("os.name", "").toLowerCase();
      if (name.contains("win")) {
        return WINDOWS;
      }
      return name.contains("mac") ? MAC : LINUX;
    }
  }
}
