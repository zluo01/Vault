package db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

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
  UPDATE_SORT_TYPE,
  UPDATE_THEME;

  private final String query = load("queries/" + name() + ".sql");

  String query() {
    return query;
  }

  static String load(final String path) {
    try (InputStream stream = DatabaseAction.class.getClassLoader().getResourceAsStream(path)) {
      final String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      if (sql.isBlank()) {
        throw new IllegalStateException("Empty sql query file " + path);
      }
      return sql;
    } catch (IOException e) {
      throw new UncheckedIOException("Fail to load sql query from file " + path, e);
    }
  }
}
