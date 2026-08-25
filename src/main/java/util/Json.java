package util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

public final class Json {
  private static final ObjectMapper MAPPER = new ObjectMapper();

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

  public static <T> List<T> readList(String json, Class<T> elementType) {
    try {
      return MAPPER.readValue(
          json, MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse JSON list: " + json, e);
    }
  }
}
