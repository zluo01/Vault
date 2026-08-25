package service;

import db.DatabaseService;
import enums.FolderStatus;
import enums.SortType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import parser.CoverImageConverter;
import parser.LibraryScanner;
import util.PathUtils;

public final class LibraryService implements AutoCloseable {

  private static final Logger LOGGER = LogManager.getLogger(LibraryService.class);

  private static final int WORKER_THREADS = 3;

  private final DatabaseService database;
  private final AppConfig config;
  private final ExecutorService executor;

  public LibraryService(final AppConfig config) {
    this.config = config;
    try {
      config.ensureCreated();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create application directories", e);
    }
    this.database = DatabaseService.create(config.databaseFile());
    this.database.initialization();

    this.executor =
        Executors.newFixedThreadPool(
            WORKER_THREADS, Thread.ofPlatform().name("vault-worker-", 1).daemon().factory());

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
    return supply(
        () ->
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

  public CompletableFuture<Void> deleteFolder(String name) {
    return run(
        () -> {
          database.deleteFolder(name);
          deleteRecursively(config.coversDir().resolve(name));
        });
  }

  public CompletableFuture<Integer> insertFolder(String name, String path) {
    return supply(() -> database.insertFolderData(name, Path.of(path)));
  }

  public CompletableFuture<Void> build(String name, String path, int position) {
    return run(() -> buildBlocking(name, Path.of(path), position));
  }

  public CompletableFuture<Void> changePathAndBuild(
      final String name, final String path, final int position) {
    return run(
        () -> {
          final Path folderPath = Path.of(path);
          database.updateFolderPath(position, folderPath);
          buildBlocking(name, folderPath, position);
        });
  }

  private <T> CompletableFuture<T> supply(final Supplier<T> task) {
    return CompletableFuture.supplyAsync(task, executor);
  }

  private CompletableFuture<Void> run(final Runnable task) {
    return CompletableFuture.runAsync(task, executor);
  }

  private void buildBlocking(final String name, final Path path, final int position) {
    database.updateFolderStatus(FolderStatus.LOADING, position);
    try {
      final var skipFolders = new HashSet<>(database.getSettings().skipFolders());
      final var parsedMedia = LibraryScanner.scan(path, skipFolders);
      final Thread covers = Thread.startVirtualThread(() -> convertCovers(name, path, parsedMedia));
      database.insertNewMedia(name, parsedMedia);
      covers.join();
      database.updateFolderStatus(FolderStatus.NONE, position);
    } catch (RuntimeException e) {
      LOGGER.error("Failed to build library {}: {}", name, e.getMessage(), e);
      database.updateFolderStatus(FolderStatus.ERROR, position);
      throw e;
    } catch (IOException e) {
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private void convertCovers(
      final String folderName, final Path root, final List<ParsedMedia> items) {
    final Path folderCovers = config.coversDir().resolve(folderName);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (ParsedMedia item : items) {
        final Path itemPath = item.media().path();
        for (String value : posterValues(item.media())) {
          pool.execute(() -> convertCover(folderCovers, root, itemPath, value));
        }
      }
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
    };
  }

  @Override
  public void close() {
    try {
      if (executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
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
          .forEach(
              p -> {
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
