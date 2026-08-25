package model;

import enums.ThemeMode;
import java.util.Arrays;
import java.util.List;

public record Setting(boolean showSidePanel, List<String> skipFolders, ThemeMode theme) {
  public static Setting of(
      final boolean showSidePanel, final String skipFolders, final String theme) {
    List<String> folders;
    if (skipFolders == null || skipFolders.isBlank()) {
      folders = List.of();
    } else {
      folders = Arrays.stream(skipFolders.split(",")).map(String::trim).toList();
    }
    return new Setting(showSidePanel, folders, ThemeMode.valueOf(theme));
  }
}
