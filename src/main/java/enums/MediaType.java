package enums;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public enum MediaType {
  MOVIE,
  TV_SHOW,
  COMIC,
  EPISODE,
  UNKNOWN;

  private static final Map<String, MediaType> MEDIA_TYPE_MAP;

  static {
    final Map<String, MediaType> mediaTypeMap = new HashMap<>(MediaType.values().length);
    for (MediaType t : values()) {
      mediaTypeMap.put(t.name(), t);
    }
    MEDIA_TYPE_MAP = Collections.unmodifiableMap(mediaTypeMap);
  }

  public static MediaType fromName(final String name) {
    return MEDIA_TYPE_MAP.getOrDefault(name, UNKNOWN);
  }
}
