package enums;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public enum FolderStatus {
  NONE,
  LOADING,
  ERROR;

  private static final Map<String, FolderStatus> FOLDER_STATUS_MAP;

  static {
    final Map<String, FolderStatus> folderStatusMap = new HashMap<>(FolderStatus.values().length);
    for (FolderStatus s : values()) {
      folderStatusMap.put(s.name(), s);
    }
    FOLDER_STATUS_MAP = Collections.unmodifiableMap(folderStatusMap);
  }

  public static FolderStatus fromName(final String name) {
    return FOLDER_STATUS_MAP.getOrDefault(name, NONE);
  }
}
