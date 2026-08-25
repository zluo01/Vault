package model;

import java.util.Arrays;
import java.util.List;

public record Setting(boolean showSidePanel, List<String> skipFolders) {
  public static Setting of(final boolean showSidePanel, final String skipFolders) {
    List<String> folders;
    if (skipFolders == null || skipFolders.isBlank()) {
      folders = List.of();
    } else {
      folders = Arrays.stream(skipFolders.split(",")).map(String::trim).toList();
    }
    return new Setting(showSidePanel, folders);
  }
}
