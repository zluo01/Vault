package ui;

import enums.ThemeMode;
import javafx.application.ColorScheme;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.stage.PopupWindow;
import javafx.stage.WindowEvent;
import service.LibraryService;

public final class ThemeManager {

  private final ObjectProperty<ThemeMode> mode;
  private Scene scene;

  public ThemeManager(final LibraryService service) {
    mode = new SimpleObjectProperty<>(service.currentSettings().theme());
    mode.addListener(
        (_, _, now) -> {
          apply();
          service.setTheme(now);
        });
  }

  /** Attach the scene once it exists and style it for the loaded mode. */
  public void attach(final Scene scene) {
    this.scene = scene;
    apply();
  }

  public ReadOnlyObjectProperty<ThemeMode> modeProperty() {
    return mode;
  }

  public void cycle() {
    final ThemeMode[] modes = ThemeMode.values();
    mode.set(modes[(mode.get().ordinal() + 1) % modes.length]);
  }

  /** Popups live in their own scene, which follows the OS scheme, need explicit override. */
  public void watch(final PopupWindow popup) {
    popup.addEventHandler(
        WindowEvent.WINDOW_SHOWING,
        e -> {
          if (popup.getScene() != null) {
            popup.getScene().getPreferences().setColorScheme(forcedScheme());
          }
        });
  }

  private ColorScheme forcedScheme() {
    return switch (mode.get()) {
      case SYSTEM -> null;
      case LIGHT -> ColorScheme.LIGHT;
      case DARK -> ColorScheme.DARK;
    };
  }

  private void apply() {
    if (scene == null) {
      return;
    }
    final ColorScheme forced = forcedScheme();
    scene.getPreferences().setColorScheme(forced);
  }
}
