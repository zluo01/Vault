package enums;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public enum FilterType {
  OR,
  AND;

  private static final Map<String, FilterType> FILTER_TYPE_MAP;

  static {
    final Map<String, FilterType> filterTypeMap = new HashMap<>(FilterType.values().length);
    for (FilterType t : values()) {
      filterTypeMap.put(t.name(), t);
    }
    FILTER_TYPE_MAP = Collections.unmodifiableMap(filterTypeMap);
  }

  public static FilterType fromName(final String name) {
    return FILTER_TYPE_MAP.getOrDefault(name, OR);
  }
}
