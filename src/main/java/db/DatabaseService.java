package db;

import enums.FolderStatus;
import enums.SortType;
import enums.ThemeMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import model.Episode;
import model.FilterOption;
import model.FolderData;
import model.FolderStats;
import model.GroupedOption;
import model.Media;
import model.ParsedMedia;
import model.Setting;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

public interface DatabaseService extends AutoCloseable {

  static DatabaseService create(final Path sqliteFile) {
    return create("jdbc:sqlite:" + sqliteFile.toAbsolutePath());
  }

  static DatabaseService create(final String jdbcUrl) {
    SQLiteConfig config = new SQLiteConfig();
    config.enforceForeignKeys(true);
    config.setJournalMode(SQLiteConfig.JournalMode.WAL);
    config.setBusyTimeout(5000);

    SQLiteDataSource dataSource = new SQLiteDataSource(config);
    dataSource.setUrl(jdbcUrl);
    return new DatabaseServiceImpl(dataSource);
  }

  void initialization();

  Setting getSettings();

  void updateHideSidePanel(int hide);

  void updateSkipFolders(List<String> skipFolders);

  void updateTheme(ThemeMode theme);

  int insertFolderData(String name, Path path);

  List<FolderData> getFolderList();

  Map<String, FolderStats> getFolderStats();

  FolderData getFolderData(int position);

  void updateSortType(int position, SortType sortType);

  void updateFolderPath(int position, Path path);

  void updateFolderStatus(FolderStatus status, int position);

  void updateFolderFilterType(int position);

  void deleteFolder(String name);

  void recover();

  void insertNewMedia(String folderName, List<ParsedMedia> items);

  List<Media> getFolderMedia(int position, List<FilterOption> tags, Path coversDir);

  List<GroupedOption> getFolderMediaTags(int position);

  List<FilterOption> getMediaTags(Path path);

  Map<String, List<Episode>> getEpisodes(
      String showPath, String folderName, Path folderRoot, Path coversDir);

  @Override
  void close();
}
