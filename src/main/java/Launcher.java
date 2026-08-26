import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javafx.application.Application;
import org.slf4j.bridge.SLF4JBridgeHandler;
import service.AppConfig;

/** Plain entry point for the shaded fat jar. */
public final class Launcher {

  private static final String SCALE_FLAG = "glass.gtk.uiScale";
  private static final String LOG_DIR_FLAG = "vault.log.dir";

  private Launcher() {}

  static void main(String[] args) {
    final Path dataDir = AppConfig.resolveDefault().dataDir();
    if (!SingleInstance.acquire(dataDir)) {
      System.err.println("Vault is already running — activating the existing window.");
      SingleInstance.activateExisting(dataDir);
      return;
    }
    applyUiScale();
    applyLogging();
    Application.launch(VaultApplication.class, args);
  }

  private static void applyLogging() {
    if (System.getProperty(LOG_DIR_FLAG) == null) {
      System.setProperty(
          LOG_DIR_FLAG, AppConfig.resolveDefault().dataDir().resolve("logs").toString());
    }
    // route java.util.logging (JavaFX, sqlite) into slf4j/logback
    SLF4JBridgeHandler.removeHandlersForRootLogger();
    SLF4JBridgeHandler.install();
  }

  private static void applyUiScale() {
    // only apply scaling on linux.
    if (AppConfig.OS != AppConfig.Os.LINUX || System.getProperty(SCALE_FLAG) != null) {
      return;
    }
    kdeXwaylandScale()
        .map(Launcher::formatScale)
        .ifPresent(scale -> System.setProperty(SCALE_FLAG, scale));
  }

  private static Optional<Double> kdeXwaylandScale() {
    Path config = Path.of(System.getProperty("user.home"), ".config", "kwinrc");
    try {
      String group = "";
      for (String line : Files.readAllLines(config)) {
        String value = line.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
          group = value.substring(1, value.length() - 1);
        } else if ("Xwayland".equals(group) && value.startsWith("Scale=")) {
          return parseScale(value.substring("Scale=".length()));
        }
      }
    } catch (IOException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static Optional<Double> parseScale(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      double scale = Double.parseDouble(value.trim());
      return scale > 1.0 ? Optional.of(scale) : Optional.empty();
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
  }

  private static String formatScale(double scale) {
    return Double.toString(Math.min(4.0, scale));
  }
}
