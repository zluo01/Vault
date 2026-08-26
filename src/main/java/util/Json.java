package util;

import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class Json {
  private static final JsonMapper MAPPER = new JsonMapper();

  private Json() {}

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize value to JSON", e);
    }
  }

  public static <T> T read(String json, TypeReference<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse JSON: " + json, e);
    }
  }

  public static Map<String, String> readStringMap(String json) {
    return read(json, new TypeReference<>() {});
  }
}
