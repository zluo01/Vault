package ui.helper;

public final class TimeUtils {

  private TimeUtils() {}

  /**
   * Turn runtime into display text, use 400 as a divider between runtime in minutes and runtime in
   * seconds
   *
   * @param runtime runtime number in string format
   * @return runtime display text
   */
  public static String runtimeText(String runtime) {
    if (runtime == null) {
      return "—";
    }
    int total;
    try {
      total = Integer.parseInt(runtime.trim());
    } catch (NumberFormatException e) {
      return "—";
    }
    if (total <= 0) {
      return "—";
    }
    int minutes = total >= 400 ? Math.round(total / 60f) : total;
    return minutes >= 60
        ? (minutes / 60) + "H " + String.format("%02d", minutes % 60) + "M"
        : minutes + "M";
  }
}
