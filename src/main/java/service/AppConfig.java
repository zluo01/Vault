package service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record AppConfig(Path dataDir) {

  public static AppConfig resolveDefault() {
    final String os = System.getProperty("os.name", "").toLowerCase();
    final String home = System.getProperty("user.home");
    Path base;
    if (os.contains("win")) {
      String appData = System.getenv("APPDATA");
      base =
          appData != null && !appData.isBlank()
              ? Path.of(appData)
              : Path.of(home, "AppData", "Roaming");
    } else if (os.contains("mac")) {
      base = Path.of(home, "Library", "Application Support");
    } else {
      String xdg = System.getenv("XDG_DATA_HOME");
      base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".local", "share");
    }
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
}
