package enums;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public enum SortType {
  DEFAULT("Directory"),
  TITLE_ASC("Name(A-Z)"),
  TITLE_DSC("Name(Z-A)"),
  YEAR_ASC("Oldest"),
  YEAR_DSC("Newest");

  private static final Map<String, SortType> SORT_TYPE_MAP;

  static {
    final Map<String, SortType> sortTypeMap = new HashMap<>(SortType.values().length);
    for (SortType s : values()) {
      sortTypeMap.put(s.name(), s);
    }
    SORT_TYPE_MAP = Collections.unmodifiableMap(sortTypeMap);
  }

  private final String label;

  SortType(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static SortType fromName(final String name) {
    return SORT_TYPE_MAP.getOrDefault(name, DEFAULT);
  }
}
