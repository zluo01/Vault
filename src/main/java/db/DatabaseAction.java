package db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;

enum DatabaseAction {
  CLEAR_MEDIA,
  CREATE_TABLES,
  DELETE_FOLDER,
  GET_EPISODES,
  GET_FOLDER_CONTENT,
  GET_FOLDER_DATA,
  GET_FOLDER_LIST,
  GET_FOLDER_STATS,
  GET_MEDIA_TAGS,
  GET_SETTINGS,
  INSERT_EPISODE,
  INSERT_MEDIA,
  INSERT_NEW_FOLDER_DATA,
  INSERT_TAG,
  RECOVER,
  TAGS_IN_FOLDER,
  UPDATE_FOLDER_FILTER_TYPE,
  UPDATE_FOLDER_PATH,
  UPDATE_FOLDER_STATUS,
  UPDATE_HIDE_PANEL,
  UPDATE_SKIP_FOLDERS,
  UPDATE_SORT_TYPE;

  private static final Map<DatabaseAction, String> QUERY_MAP;

  static {
    final Map<DatabaseAction, String> builder = new HashMap<>();
    for (DatabaseAction action : DatabaseAction.values()) {
      builder.put(action, getSQLContent(action));
    }
    QUERY_MAP = Collections.unmodifiableMap(builder);
  }

  private static String getSQLContent(final DatabaseAction action) {
    final String sourcePath = "queries/" + action.name() + ".sql";
    try (InputStream stream =
            DatabaseAction.class.getClassLoader().getResourceAsStream(sourcePath);
        Scanner scanner = new Scanner(Objects.requireNonNull(stream), StandardCharsets.UTF_8)) {
      return scanner.useDelimiter("\\A").next();
    } catch (IOException e) {
      throw new RuntimeException("Fail to load sql query from file " + sourcePath, e);
    }
  }

  String query() {
    return QUERY_MAP.get(this);
  }
}
