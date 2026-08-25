package viewmodel;

import enums.FolderStatus;
import enums.SortType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.collections.transformation.FilteredList;
import model.Episode;
import model.FilterOption;
import model.FolderData;
import model.FolderStats;
import model.GroupedOption;
import model.Media;
import model.Movie;
import model.Setting;
import model.TvShow;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import service.LibraryService;

public final class LibraryViewModel {

  private static final Logger LOGGER = LogManager.getLogger(LibraryViewModel.class);
  private static final Executor FX = Platform::runLater;

  private final LibraryService service;
  private final Notifier notifier;
  private final HostServices hostServices;

  private final ObservableList<FolderData> folders = FXCollections.observableArrayList();
  private final ObservableMap<String, FolderStats> folderStats = FXCollections.observableHashMap();
  private final ObjectProperty<Setting> setting = new SimpleObjectProperty<>();
  private final IntegerProperty selectedFolderId = new SimpleIntegerProperty(0);
  private final ObjectProperty<FolderData> currentFolder = new SimpleObjectProperty<>();
  private final ObservableList<Media> media = FXCollections.observableArrayList();
  private final FilteredList<Media> visibleMedia = new FilteredList<>(media);
  private final ObservableList<GroupedOption> tagGroups = FXCollections.observableArrayList();
  private final StringProperty searchKey = new SimpleStringProperty("");
  private final StringProperty statusText = new SimpleStringProperty("0 ITEMS");

  // keep the tags per folder so change folder does not erase previous filtering
  private final Map<Integer, ObservableList<FilterOption>> selectedTagsByFolder = new HashMap<>();

  public LibraryViewModel(
      final LibraryService service, final Notifier notifier, final HostServices hostServices) {
    this.service = service;
    this.notifier = notifier;
    this.hostServices = hostServices;

    visibleMedia
        .predicateProperty()
        .bind(Bindings.createObjectBinding(this::searchPredicate, searchKey));
    visibleMedia.addListener(
        (javafx.collections.ListChangeListener<Media>) _ -> resetStatusToCount());
    selectedFolderId.addListener((_, _, _) -> loadSelectedFolder());
  }

  public void start() {
    refreshSettings();
    refreshFolders();
    loadSelectedFolder();
  }

  public ObservableList<FolderData> folders() {
    return folders;
  }

  public ObservableMap<String, FolderStats> folderStats() {
    return folderStats;
  }

  public ObjectProperty<Setting> settingProperty() {
    return setting;
  }

  public IntegerProperty selectedFolderIdProperty() {
    return selectedFolderId;
  }

  public ObjectProperty<FolderData> currentFolderProperty() {
    return currentFolder;
  }

  public ObservableList<Media> visibleMedia() {
    return visibleMedia;
  }

  public ObservableList<GroupedOption> tagGroups() {
    return tagGroups;
  }

  public StringProperty searchKeyProperty() {
    return searchKey;
  }

  public StringProperty statusTextProperty() {
    return statusText;
  }

  public ObservableList<FilterOption> selectedTags(final int folderId) {
    return selectedTagsByFolder.computeIfAbsent(folderId, _ -> FXCollections.observableArrayList());
  }

  public void setStatus(final String text) {
    statusText.set(text);
  }

  public void resetStatusToCount() {
    statusText.set(visibleMedia.size() + " ITEMS");
  }

  public void selectFolder(final int folderId) {
    searchKey.set("");
    if (folderId == selectedFolderId.get()) {
      loadSelectedFolder();
    } else {
      selectedFolderId.set(folderId);
    }
  }

  private void refreshSettings() {
    service.settings().thenAcceptAsync(setting::set, FX).exceptionally(this::logFailure);
  }

  private void refreshFolders() {
    service.folderList().thenAcceptAsync(folders::setAll, FX).exceptionally(this::logFailure);
    service
        .folderStats()
        .thenAcceptAsync(
            stats -> {
              // use retainAll instead of clear to prevent unnecessary trigger when no value changes
              folderStats.keySet().retainAll(stats.keySet());
              folderStats.putAll(stats);
            },
            FX)
        .exceptionally(this::logFailure);
  }

  private void loadSelectedFolder() {
    final int id = selectedFolderId.get();
    service
        .mediaTags(id)
        .thenAcceptAsync(
            tags -> {
              if (id == selectedFolderId.get()) {
                tagGroups.setAll(tags);
              }
            },
            FX)
        .exceptionally(this::logFailure);
    service
        .folderData(id)
        .thenAcceptAsync(
            data -> {
              if (id == selectedFolderId.get()) {
                currentFolder.set(data);
                reloadMedia();
              }
            },
            FX)
        .exceptionally(this::logFailure);
  }

  private void reloadMedia() {
    final int id = selectedFolderId.get();
    final List<FilterOption> tags = List.copyOf(selectedTags(id));
    service
        .folderMedia(id, tags)
        .thenAcceptAsync(
            result -> {
              if (id == selectedFolderId.get()) {
                media.setAll(result);
              }
            },
            FX)
        .exceptionally(this::logFailure);
  }

  public void fetchMediaTags(final Media media, final Consumer<List<FilterOption>> onLoaded) {
    service
        .mediaItemTags(media.path())
        .thenAcceptAsync(onLoaded, FX)
        .exceptionally(this::logFailure);
  }

  public void fetchEpisodes(
      final TvShow show, final Consumer<Map<String, List<Episode>>> onLoaded) {
    final FolderData folder = currentFolder.get();
    if (folder == null) {
      onLoaded.accept(Map.of());
      return;
    }
    service
        .episodes(show.path(), folder.name(), Path.of(folder.path()))
        .thenAcceptAsync(onLoaded, FX)
        .exceptionally(this::logFailure);
  }

  public void updateSort(final SortType sort) {
    mutateFolder(id -> service.updateSortType(id, sort), "Update Sort Error");
  }

  public void switchFilterType() {
    mutateFolder(service::switchFilterType, "Switch Filter Type Error");
  }

  private void mutateFolder(
      final IntFunction<CompletableFuture<Void>> action, final String errorPrefix) {
    final int id = selectedFolderId.get();
    action
        .apply(id)
        .thenCompose(ignored -> service.folderData(id))
        .thenAcceptAsync(
            data -> {
              if (id == selectedFolderId.get()) {
                currentFolder.set(data);
                reloadMedia();
              }
            },
            FX)
        .exceptionally(
            error -> {
              notifier.show(errorPrefix + ": " + rootMessage(error));
              return null;
            });
  }

  public boolean hasTag(final int folderId, final FilterOption tag) {
    return selectedTags(folderId).contains(tag);
  }

  public void modifyTag(final int folderId, final FilterOption tag) {
    final ObservableList<FilterOption> tags = selectedTags(folderId);
    if (!tags.remove(tag)) {
      tags.add(tag);
    }
    if (folderId == selectedFolderId.get()) {
      reloadMedia();
    }
  }

  public void clearTags(final int folderId) {
    final ObservableList<FilterOption> tags = selectedTags(folderId);
    if (!tags.isEmpty()) {
      tags.clear();
      if (folderId == selectedFolderId.get()) {
        reloadMedia();
      }
    }
  }

  public void removeTagFolder(final int folderId) {
    selectedTagsByFolder.remove(folderId);
  }

  public void addLibrary(final String name, final String path) {
    service
        .insertFolder(name, path)
        .thenAcceptAsync(
            position -> {
              refreshFolders();
              buildAndRefresh(name, path, position);
            },
            FX)
        .exceptionally(
            error -> {
              notifier.show("Import Folder Error: " + rootMessage(error));
              return null;
            });
  }

  public void refreshLibrary(final String name, final String path, final int position) {
    buildAndRefresh(name, path, position);
  }

  public void changeFolderPath(final String name, final int position, final String path) {
    optimisticLoading(position);
    service
        .changePathAndBuild(name, path, position)
        .whenCompleteAsync((ignored, error) -> onBuildComplete(name, position, error), FX);
  }

  private void buildAndRefresh(final String name, final String path, final int position) {
    optimisticLoading(position);
    service
        .build(name, path, position)
        .whenCompleteAsync((ignored, error) -> onBuildComplete(name, position, error), FX);
  }

  private void onBuildComplete(String name, int position, Throwable error) {
    refreshFolders();
    if (position == selectedFolderId.get()) {
      loadSelectedFolder();
    }
    if (error == null) {
      notifier.show("Building directory " + name + " is finished.");
    } else {
      notifier.show("Update Library Error: " + rootMessage(error));
    }
  }

  private void optimisticLoading(final int position) {
    final FolderData folder = currentFolder.get();
    if (position == selectedFolderId.get() && folder != null) {
      currentFolder.set(folder.withStatus(FolderStatus.LOADING));
    }
  }

  public void deleteFolder(final FolderData folder) {
    service
        .deleteFolder(folder.name())
        .thenCompose(ignored -> service.folderList())
        .thenAcceptAsync(
            list -> {
              folders.setAll(list);
              removeTagFolder(folder.position());
            },
            FX)
        .exceptionally(
            error -> {
              notifier.show("Update Folder Error: " + rootMessage(error));
              refreshFolders();
              return null;
            });
  }

  public void setShowSidePanel(final boolean show) {
    mutateSettings(() -> service.setShowSidePanel(show), "Update Setting Error");
  }

  public void addSkipFolder(final String name) {
    final List<String> current = currentSkipFolders();
    if (current.contains(name)) {
      return;
    }
    final List<String> updated = new ArrayList<>(current);
    updated.add(name);
    updateSkipFolders(updated);
  }

  public void removeSkipFolder(final String name) {
    updateSkipFolders(currentSkipFolders().stream().filter(f -> !f.equals(name)).toList());
  }

  private void updateSkipFolders(final List<String> skipFolderList) {
    mutateSettings(() -> service.updateSkipFolders(skipFolderList), "Skip Folder Error");
  }

  private void mutateSettings(
      final Supplier<CompletableFuture<Void>> action, final String errorPrefix) {
    action
        .get()
        .thenCompose(ignored -> service.settings())
        .thenAcceptAsync(setting::set, FX)
        .exceptionally(
            error -> {
              notifier.show(errorPrefix + ": " + rootMessage(error));
              return null;
            });
  }

  private List<String> currentSkipFolders() {
    final Setting current = setting.get();
    return current == null ? List.of() : current.skipFolders();
  }

  public void openMedia(final Media media) {
    // TV shows are opened via the season/episode menu, handled by the UI.
    if (Objects.requireNonNull(media) instanceof Movie movie) {
      open(movie.path().resolve(movie.file()));
    }
  }

  public void openEpisode(final Episode episode) {
    open(episode.path().resolve(episode.file()));
  }

  public void openContainingFolder(final Media media) {
    open(media.path());
  }

  private void open(final Path target) {
    hostServices.showDocument(target.toUri().toString());
  }

  private Predicate<? super Media> searchPredicate() {
    final String raw = searchKey.get();
    final String key = raw == null ? "" : raw.trim().toLowerCase();
    return key.isEmpty() ? _ -> true : item -> item.title().toLowerCase().contains(key);
  }

  private Void logFailure(final Throwable error) {
    LOGGER.error("Background operation failed", error);
    return null;
  }

  private static String rootMessage(final Throwable error) {
    Throwable cause =
        error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    return cause.getMessage() != null ? cause.getMessage() : cause.toString();
  }
}
