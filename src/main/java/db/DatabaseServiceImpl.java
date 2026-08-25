package db;

import enums.FilterType;
import enums.FolderStatus;
import enums.MediaType;
import enums.SortType;
import enums.TagCategory;
import enums.ThemeMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import model.Episode;
import model.FilterOption;
import model.FolderData;
import model.FolderStats;
import model.GroupedOption;
import model.Media;
import model.Movie;
import model.ParsedMedia;
import model.Setting;
import model.TvShow;
import org.apache.logging.log4j.util.Strings;
import org.sqlite.SQLiteDataSource;
import util.Json;
import util.PathUtils;

final class DatabaseServiceImpl implements DatabaseService {

  private final Connection connection;

  DatabaseServiceImpl(final SQLiteDataSource dataSource) {
    try {
      this.connection = dataSource.getConnection();
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to open database connection", e);
    }
  }

  @Override
  public synchronized void initialization() {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(DatabaseAction.CREATE_TABLES.query());
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to initialize database schema", e);
    }
  }

  @Override
  public Setting getSettings() {
    return queryOne(
        DatabaseAction.GET_SETTINGS.query(),
        rs ->
            Setting.of(
                rs.getInt("hide_panel") == 0, rs.getString("skip_folders"), rs.getString("theme")));
  }

  @Override
  public void updateHideSidePanel(final int hide) {
    update(DatabaseAction.UPDATE_HIDE_PANEL.query(), hide);
  }

  @Override
  public void updateSkipFolders(final List<String> skipFolders) {
    update(DatabaseAction.UPDATE_SKIP_FOLDERS.query(), Strings.join(skipFolders, ','));
  }

  @Override
  public void updateTheme(final ThemeMode theme) {
    update(DatabaseAction.UPDATE_THEME.query(), theme.name());
  }

  @Override
  public int insertFolderData(final String name, final Path path) {
    return queryOne(
        DatabaseAction.INSERT_NEW_FOLDER_DATA.query(), rs -> rs.getInt("position"), name, path);
  }

  @Override
  public List<FolderData> getFolderList() {
    return queryList(DatabaseAction.GET_FOLDER_LIST.query(), DatabaseServiceImpl::mapFolder);
  }

  @Override
  public Map<String, FolderStats> getFolderStats() {
    List<Map.Entry<String, FolderStats>> rows =
        queryList(
            DatabaseAction.GET_FOLDER_STATS.query(),
            rs ->
                Map.entry(
                    rs.getString("folder"),
                    new FolderStats(rs.getInt("items"), rs.getInt("types"))));
    Map<String, FolderStats> stats = new HashMap<>();
    for (Map.Entry<String, FolderStats> entry : rows) {
      stats.put(entry.getKey(), entry.getValue());
    }
    return stats;
  }

  @Override
  public FolderData getFolderData(final int position) {
    return queryOne(
        DatabaseAction.GET_FOLDER_DATA.query(), DatabaseServiceImpl::mapFolder, position);
  }

  private static FolderData mapFolder(final ResultSet rs) throws SQLException {
    return new FolderData(
        rs.getString("name"),
        rs.getInt("position"),
        rs.getString("path"),
        SortType.fromName(rs.getString("sort_type")),
        FilterType.fromName(rs.getString("filter_type")),
        FolderStatus.fromName(rs.getString("status")));
  }

  @Override
  public void updateSortType(final int position, final SortType sortType) {
    update(DatabaseAction.UPDATE_SORT_TYPE.query(), sortType.name(), position);
  }

  @Override
  public void updateFolderPath(final int position, final Path path) {
    update(DatabaseAction.UPDATE_FOLDER_PATH.query(), path, position);
  }

  @Override
  public void updateFolderStatus(final FolderStatus status, final int position) {
    update(DatabaseAction.UPDATE_FOLDER_STATUS.query(), status.name(), position);
  }

  @Override
  public void updateFolderFilterType(final int position) {
    update(DatabaseAction.UPDATE_FOLDER_FILTER_TYPE.query(), position);
  }

  @Override
  public void deleteFolder(final String name) {
    update(DatabaseAction.DELETE_FOLDER.query(), name);
  }

  @Override
  public void recover() {
    update(DatabaseAction.RECOVER.query());
  }

  @Override
  public void insertNewMedia(final String folderName, final List<ParsedMedia> items) {
    if (items.isEmpty()) {
      return;
    }
    inTransaction(
        connection -> {
          clearFolder(connection, folderName);
          insertMedia(connection, folderName, items);
          insertEpisodes(connection, items);
          insertTags(connection, items);
        });
  }

  @Override
  public synchronized List<Media> getFolderMedia(
      int position, List<FilterOption> tags, Path coversDir) {
    String tagsJson = Json.write(tags);
    List<Media> result = new ArrayList<>();
    try (PreparedStatement ps =
        connection.prepareStatement(DatabaseAction.GET_FOLDER_CONTENT.query())) {
      ps.setString(1, tagsJson);
      ps.setInt(2, position);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Media media = mapMedia(rs, coversDir);
          if (media != null) {
            result.add(media);
          }
        }
      }
    } catch (SQLException e) {
      throw new DataAccessException("Failed to get folder media", e);
    }
    return result;
  }

  @Override
  public List<GroupedOption> getFolderMediaTags(int position) {
    List<FilterOption> rows =
        queryList(
            DatabaseAction.TAGS_IN_FOLDER.query(),
            rs -> new FilterOption(rs.getString("category"), rs.getString("name")),
            position);
    // Rows arrive ordered by category then name; the insertion order carries into the groups.
    Map<String, List<FilterOption>> groups = new LinkedHashMap<>();
    for (FilterOption row : rows) {
      groups.computeIfAbsent(row.group(), _ -> new ArrayList<>()).add(row);
    }
    return groups.entrySet().stream()
        .map(entry -> new GroupedOption(entry.getKey(), entry.getValue()))
        .toList();
  }

  @Override
  public List<FilterOption> getMediaTags(Path path) {
    return queryList(
        DatabaseAction.GET_MEDIA_TAGS.query(),
        rs -> new FilterOption(rs.getString("category"), rs.getString("name")),
        path);
  }

  @Override
  public Map<String, List<Episode>> getEpisodes(
      String showPath, String folderName, Path folderRoot, Path coversDir) {
    List<Episode> episodes =
        queryList(
            DatabaseAction.GET_EPISODES.query(),
            rs -> {
              Path episodePath = Path.of(rs.getString("path"));
              return Episode.builder()
                  .title(rs.getString("title"))
                  .file(rs.getString("file"))
                  .season(rs.getString("season"))
                  .episode(rs.getString("episode"))
                  .runtime(rs.getString("runtime"))
                  .path(episodePath)
                  .preview(
                      coverUrl(
                          folderName, folderRoot, episodePath, rs.getString("preview"), coversDir))
                  .build();
            },
            showPath);
    final Map<String, List<Episode>> seasons = new LinkedHashMap<>();
    for (Episode episode : episodes) {
      seasons.computeIfAbsent(seasonKey(episode.season()), _ -> new ArrayList<>()).add(episode);
    }
    return seasons;
  }

  @Override
  public synchronized void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new DataAccessException("Failed to close database connection", e);
    }
  }

  private static String seasonKey(String season) {
    try {
      return String.format("%02d", Integer.parseInt(season));
    } catch (NumberFormatException e) {
      return season;
    }
  }

  private static String coverUrl(
      String folderName, Path folderRoot, Path itemPath, String value, Path coversDir) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    Path folderCovers = coversDir.resolve(folderName);
    String relative =
        PathUtils.stripImageExtensions(folderRoot.relativize(itemPath).resolve(value));
    return PathUtils.resolveRelative(folderCovers, relative).toUri().toString();
  }

  private static void clearFolder(Connection connection, String folderName) throws SQLException {
    try (PreparedStatement clearMedia =
        connection.prepareStatement(DatabaseAction.CLEAR_MEDIA.query())) {
      clearMedia.setString(1, folderName);
      clearMedia.executeUpdate();
    }
  }

  private static void insertMedia(
      final Connection connection, final String folderName, final List<ParsedMedia> items)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(DatabaseAction.INSERT_MEDIA.query())) {
      for (ParsedMedia item : items) {
        switch (item.media()) {
          case Movie movie -> {
            ps.setString(1, MediaType.MOVIE.name());
            ps.setString(2, movie.path().toString());
            ps.setString(3, movie.title());
            ps.setString(
                4, Json.write(movie.poster() == null ? Map.of() : Map.of("main", movie.poster())));
            ps.setString(5, movie.year());
            ps.setString(6, movie.runtime());
            ps.setString(7, movie.file());
            ps.setString(8, folderName);
            ps.addBatch();
          }
          case TvShow show -> {
            ps.setString(1, MediaType.TV_SHOW.name());
            ps.setString(2, show.path().toString());
            ps.setString(3, show.title());
            ps.setString(4, Json.write(show.posters()));
            ps.setString(5, null);
            ps.setString(6, null);
            ps.setString(7, null);
            ps.setString(8, folderName);
            ps.addBatch();
          }
          case Episode ignored -> {}
        }
      }
      ps.executeBatch();
    }
  }

  private static void insertEpisodes(final Connection connection, final List<ParsedMedia> items)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(DatabaseAction.INSERT_EPISODE.query())) {
      for (ParsedMedia item : items) {
        if (!(item.media()
            instanceof
            Episode(
                String title,
                String file,
                String season,
                String episode1,
                String runtime,
                Path path,
                String preview))) {
          continue;
        }
        ps.setString(1, path.getParent().toString());
        ps.setString(2, path.toString());
        ps.setString(3, title);
        ps.setString(4, file);
        ps.setString(5, season);
        ps.setString(6, episode1);
        ps.setString(7, runtime);
        ps.setString(8, preview);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static void insertTags(final Connection connection, final List<ParsedMedia> items)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(DatabaseAction.INSERT_TAG.query())) {
      for (ParsedMedia item : items) {
        final String path = item.media().path().toString();
        for (TagCategory category : TagCategory.values()) {
          addTags(ps, path, item.tags(category), category.name());
        }
      }
      ps.executeBatch();
    }
  }

  private static void addTags(PreparedStatement ps, String path, List<String> tags, String type)
      throws SQLException {
    for (String tag : tags) {
      ps.setString(1, path);
      ps.setString(2, tag);
      ps.setString(3, type);
      ps.addBatch();
    }
  }

  private Media mapMedia(final ResultSet rs, final Path coversDir) throws SQLException {
    final MediaType type = MediaType.fromName(rs.getString("t"));
    final Path folderPath = Path.of(rs.getString("path"));
    final Path folderRoot = Path.of(rs.getString("folder_path"));
    final Map<String, String> posters =
        buildPosterUrls(
            type,
            rs.getString("folder_name"),
            rs.getString("posters"),
            folderPath,
            folderRoot,
            coversDir);
    return switch (type) {
      case MOVIE -> mapMovie(rs, folderPath, posters);
      case TV_SHOW -> mapTvShow(rs, folderPath, posters);
      default -> null;
    };
  }

  private static Movie mapMovie(
      final ResultSet rs, final Path folderPath, final Map<String, String> posters)
      throws SQLException {
    return Movie.builder()
        .title(rs.getString("title"))
        .path(folderPath)
        .poster(posters.get("main"))
        .year(rs.getString("year"))
        .runtime(rs.getString("runtime"))
        .file(rs.getString("file"))
        .build();
  }

  private static TvShow mapTvShow(
      final ResultSet rs, final Path folderPath, final Map<String, String> posters)
      throws SQLException {
    return TvShow.builder().title(rs.getString("title")).path(folderPath).posters(posters).build();
  }

  private Map<String, String> buildPosterUrls(
      final MediaType type,
      final String folderName,
      final String postersJson,
      final Path folderPath,
      final Path folderRoot,
      final Path coversDir) {
    if (postersJson == null || postersJson.isEmpty()) {
      return Map.of();
    }
    final Map<String, String> raw = Json.readStringMap(postersJson);
    final Map<String, String> urls = new HashMap<>();
    final Path folderCovers = coversDir.resolve(folderName);
    for (Map.Entry<String, String> entry : raw.entrySet()) {
      final String value = entry.getValue();
      final String relative =
          switch (type) {
            case MOVIE, TV_SHOW ->
                PathUtils.stripImageExtensions(folderRoot.relativize(folderPath).resolve(value));
            case COMIC -> PathUtils.stripImageExtensions(value);
            default -> value;
          };
      urls.put(
          entry.getKey(), PathUtils.resolveRelative(folderCovers, relative).toUri().toString());
    }
    return urls;
  }

  @FunctionalInterface
  private interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

  @FunctionalInterface
  private interface TxWork {
    void run(Connection connection) throws SQLException;
  }

  private synchronized <T> T queryOne(
      final String sql, final RowMapper<T> mapper, final Object... params) {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new DataAccessException("Expected a row but found none: " + sql, null);
        }
        return mapper.map(rs);
      }
    } catch (SQLException e) {
      throw new DataAccessException("Query failed: " + sql, e);
    }
  }

  private synchronized <T> List<T> queryList(
      final String sql, final RowMapper<T> mapper, final Object... params) {
    List<T> result = new ArrayList<>();
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      bind(ps, params);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(mapper.map(rs));
        }
      }
    } catch (SQLException e) {
      throw new DataAccessException("Query failed: " + sql, e);
    }
    return result;
  }

  private synchronized void update(final String sql, final Object... params) {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      bind(ps, params);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new DataAccessException("Update failed: " + sql, e);
    }
  }

  private synchronized void inTransaction(final TxWork work) {
    try {
      connection.setAutoCommit(false);
      try {
        work.run(connection);
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new DataAccessException("Transaction failed", e);
    }
  }

  private static void bind(final PreparedStatement ps, final Object... params) throws SQLException {
    for (int i = 0; i < params.length; i++) {
      ps.setObject(i + 1, params[i]);
    }
  }
}
