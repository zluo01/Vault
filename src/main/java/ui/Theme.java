package ui;

import javafx.animation.Interpolator;
import javafx.scene.paint.Color;

/**
 * Shared theme constants for the monochrome "Vault" design language (mirrors the palette defined in
 * {@code css/app.css}) plus the eased motion curves used by every animation.
 */
public final class Theme {

  public static final String STYLESHEET = "/css/app.css";

  // ---- Palette (string form, used for inline styles exactly like the Vault reference) ----
  public static final String BG = "#060607";
  public static final String TEXT = "#f2f2f0";
  public static final String MID = "#cfd0d2";
  public static final String DIM2 = "#6f7176";
  public static final String FAINT = "#46484e";
  public static final String ACCENT = TEXT; // monochrome by default

  public static final Color BACKGROUND = Color.web(BG);

  /** ease-out: fast start, gentle settle — for entrances and hover motion. */
  public static final Interpolator EASE_OUT = Interpolator.SPLINE(0.16, 1, 0.3, 1);

  /** ease-in-out: for reversible motion like the sidebar collapse. */
  public static final Interpolator EASE_IN_OUT = Interpolator.SPLINE(0.65, 0, 0.35, 1);

  /** Monochrome poster tones (JavaFX gradient strings) shown while covers load or are missing. */
  public static String tone(int i) {
    return switch (Math.floorMod(i, 4)) {
      case 0 -> "linear-gradient(from 0% 0% to 100% 100%, #1c1d1f, #0a0a0b)";
      case 1 -> "linear-gradient(from 0% 0% to 100% 100%, #161719, #08080a)";
      case 2 -> "linear-gradient(from 0% 0% to 100% 100%, #202123, #0c0c0e)";
      default -> "linear-gradient(from 0% 0% to 100% 100%, #141517, #070708)";
    };
  }

  private Theme() {}
}
