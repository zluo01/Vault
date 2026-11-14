package util;

import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class Json {
  private static final JsonMapper MAPPER = new JsonMapper();

  private Json() {}

  public static String write(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  public static Map<String, String> readStringMap(String json) {
    return MAPPER.readValue(json, new TypeReference<>() {});
  }
}
