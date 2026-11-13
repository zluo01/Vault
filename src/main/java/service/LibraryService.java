package service;

import db.DatabaseService;
import enums.FolderStatus;
import enums.SortType;
import enums.ThemeMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import model.Comic;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import parser.ComicParser;
import parser.CoverImageConverter;
import parser.LibraryScanner;
import util.PathUtils;

public final class LibraryService implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(LibraryService.class);

  private static final int QUERY_THREADS = 3;
  private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

  /** Finds and parses the media under a library root. */
  @FunctionalInterface
  public interface MediaScanner {
    List<ParsedMedia> scan(Path root, Set<String> skipFolders) throws IOException;
  }

  private final DatabaseService database;
  private final AppConfig config;
  private final MediaScanner scanner;

  private final ExecutorService queries;

  // Builds run for as long as a scan takes, so a query from the UI must never queue behind one.
  // Each build gets a virtual thread of its own and starts at once.
  private final ExecutorService builds;

  public LibraryService(final AppConfig config) {
    this(config, LibraryScanner::scan);
  }

  /** As above, with the directory scan replaced; lets tests control how a build behaves. */
  public LibraryService(final AppConfig config, final MediaScanner scanner) {
    this.config = config;
    this.scanner = scanner;
    try {
      config.ensureCreated();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create application directories", e);
    }
    this.database = DatabaseService.create(config.databaseFile());
    this.database.initialization();

    this.queries =
        Executors.newFixedThreadPool(
            QUERY_THREADS, Thread.ofPlatform().name("vault-worker-", 1).daemon().factory());
    this.builds =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("vault-build-", 1).factory());

    database.recover();
  }

  public CompletableFuture<Setting> settings() {
    return supply(database::getSettings);
  }

  public CompletableFuture<List<FolderData>> folderList() {
    return supply(database::getFolderList);
  }

  public CompletableFuture<Map<String, FolderStats>> folderStats() {
    return supply(database::getFolderStats);
  }

  public CompletableFuture<FolderData> folderData(int position) {
    return supply(() -> database.getFolderData(position));
  }

  public CompletableFuture<List<Media>> folderMedia(int position, List<FilterOption> tags) {
    return supply(() -> database.getFolderMedia(position, tags, config.coversDir()));
  }

  public CompletableFuture<List<GroupedOption>> mediaTags(int position) {
    return supply(() -> database.getFolderMediaTags(position));
  }

  public CompletableFuture<List<FilterOption>> mediaItemTags(Path path) {
    return supply(() -> database.getMediaTags(path));
  }

  public CompletableFuture<Map<String, List<Episode>>> episodes(
      final Path showPath, final String folderName, final Path folderRoot) {
    return supply(() ->
        database.getEpisodes(showPath.toString(), folderName, folderRoot, config.coversDir()));
  }

  public CompletableFuture<Void> updateSortType(int position, SortType sortType) {
    return run(() -> database.updateSortType(position, sortType));
  }

  public CompletableFuture<Void> switchFilterType(int position) {
    return run(() -> database.updateFolderFilterType(position));
  }

  public CompletableFuture<Void> updateSkipFolders(final List<String> skipFolderList) {
    return run(() -> database.updateSkipFolders(skipFolderList));
  }

  public CompletableFuture<Void> setShowSidePanel(boolean show) {
    return run(() -> database.updateHideSidePanel(show ? 0 : 1));
  }

  public Setting currentSettings() {
    return database.getSettings();
  }

  public void setTheme(ThemeMode theme) {
    run(() -> database.updateTheme(theme));
  }

  public CompletableFuture<Void> deleteFolder(String name) {
    return run(() -> {
      database.deleteFolder(name);
      deleteRecursively(config.coversDir().resolve(name));
    });
  }

  public CompletableFuture<Integer> insertFolder(String name, String path) {
    return supply(() -> database.insertFolderData(name, Path.of(path)));
  }

  public CompletableFuture<Void> build(String name, String path, int position) {
    return CompletableFuture.runAsync(() -> buildBlocking(name, Path.of(path), position), builds);
  }

  public CompletableFuture<Void> changePathAndBuild(
      final String name, final String path, final int position) {
    return CompletableFuture.runAsync(
        () -> {
          final Path folderPath = Path.of(path);
          database.updateFolderPath(position, folderPath);
          buildBlocking(name, folderPath, position);
        },
        builds);
  }

  private <T> CompletableFuture<T> supply(final Supplier<T> task) {
    return CompletableFuture.supplyAsync(task, queries);
  }

  private CompletableFuture<Void> run(final Runnable task) {
    return CompletableFuture.runAsync(task, queries);
  }

  private void buildBlocking(final String name, final Path path, final int position) {
    database.updateFolderStatus(FolderStatus.LOADING, position);
    try {
      final var skipFolders = new HashSet<>(database.getSettings().skipFolders());
      final var parsedMedia = scanner.scan(path, skipFolders);
      final Thread covers = Thread.startVirtualThread(() -> convertCovers(name, path, parsedMedia));
      database.insertNewMedia(name, parsedMedia);
      covers.join();
      database.updateFolderStatus(FolderStatus.NONE, position);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw failBuild(name, position, e);
    } catch (IOException | RuntimeException e) {
      throw failBuild(name, position, e);
    }
  }

  /**
   * Record a failed build on its folder, so no exit leaves it in LOADING; returns what to throw.
   */
  private RuntimeException failBuild(final String name, final int position, final Exception cause) {
    LOGGER.error("Failed to build library {}: {}", name, cause.getMessage(), cause);
    database.updateFolderStatus(FolderStatus.ERROR, position);
    return cause instanceof RuntimeException runtime ? runtime : new RuntimeException(cause);
  }

  private void convertCovers(
      final String folderName, final Path root, final List<ParsedMedia> items) {
    final Path folderCovers = config.coversDir().resolve(folderName);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (ParsedMedia item : items) {
        if (item.media() instanceof Comic comic) {
          pool.execute(() -> convertComicCover(folderCovers, comic));
          continue;
        }
        final Path itemPath = item.media().path();
        for (String value : posterValues(item.media())) {
          pool.execute(() -> convertCover(folderCovers, root, itemPath, value));
        }
      }
    }
  }

  private static void convertComicCover(final Path folderCovers, final Comic comic) {
    try {
      final Path dest = PathUtils.resolveRelative(folderCovers, comic.poster());
      Files.createDirectories(dest.getParent());
      CoverImageConverter.convert(ComicParser.extractCover(comic.path()), dest);
    } catch (IOException e) {
      LOGGER.warn("Failed to extract cover for {}: {}", comic.path(), e.getMessage());
    }
  }

  private static void convertCover(
      final Path folderCovers, final Path root, final Path itemPath, final String value) {
    try {
      final Path source = itemPath.resolve(value);
      final Path dest =
          PathUtils.resolveRelative(
              folderCovers, PathUtils.stripImageExtensions(root.relativize(source)));
      Files.createDirectories(dest.getParent());
      CoverImageConverter.convert(source, dest);
    } catch (IOException e) {
      LOGGER.warn("Failed to convert cover {} for {}: {}", value, itemPath, e.getMessage());
    }
  }

  private static List<String> posterValues(final Media media) {
    return switch (media) {
      case Movie movie -> movie.poster() == null ? List.of() : List.of(movie.poster());
      case TvShow show -> List.copyOf(show.posters().values());
      case Episode episode -> episode.preview() == null ? List.of() : List.of(episode.preview());
      case Comic _ -> throw new IllegalStateException("Unexpected handling on comic media type.");
    };
  }

  @Override
  public void close() {
    // Stop taking work, give what is already running a moment to finish, then interrupt the rest.
    final List<ExecutorService> pools = List.of(queries, builds);
    pools.forEach(ExecutorService::shutdown);
    try {
      final long deadline = System.nanoTime() + SHUTDOWN_GRACE.toNanos();
      for (ExecutorService pool : pools) {
        if (!pool.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
          pool.shutdownNow();
        }
      }
    } catch (InterruptedException e) {
      pools.forEach(ExecutorService::shutdownNow);
      Thread.currentThread().interrupt();
    }
    database.close();
  }

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(p -> {
            try {
              Files.delete(p);
            } catch (IOException e) {
              LOGGER.warn("Failed to delete {}: {}", p, e.getMessage());
            }
          });
    } catch (IOException e) {
      LOGGER.warn("Failed to delete cover directory {}: {}", root, e.getMessage());
    }
  }
}
