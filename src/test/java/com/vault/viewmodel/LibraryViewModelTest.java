package com.vault.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import enums.FilterType;
import enums.FolderStatus;
import enums.SortType;
import enums.ThemeMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.collections.transformation.FilteredList;
import model.FilterOption;
import model.FolderData;
import model.Media;
import model.Movie;
import model.Setting;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import service.AppConfig.Os;
import service.LibraryService;
import viewmodel.LibraryViewModel;

class LibraryViewModelTest {

  private LibraryService service;
  private HostServices hostServices;
  private List<String> notifications;
  private LibraryViewModel viewModel;

  @BeforeAll
  static void initToolkit() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException alreadyRunning) {
      // toolkit started by an earlier test class
    }
  }

  @BeforeEach
  void setUp() {
    // every service call yields a fresh, never-completing future unless a test stubs it
    service =
        mock(
            LibraryService.class,
            invocation ->
                CompletableFuture.class.equals(invocation.getMethod().getReturnType())
                    ? new CompletableFuture<>()
                    : Mockito.RETURNS_DEFAULTS.answer(invocation));
    hostServices = mock(HostServices.class);
    notifications = new ArrayList<>();
    viewModel = new LibraryViewModel(service, notifications::add, hostServices, Os.LINUX);
  }

  /** Wait until all Platform.runLater work queued so far (and by it) has run. */
  private static void drainFx() throws InterruptedException {
    for (int i = 0; i < 3; i++) {
      CountDownLatch latch = new CountDownLatch(1);
      Platform.runLater(latch::countDown);
      assertTrue(latch.await(5, TimeUnit.SECONDS), "FX queue did not drain");
    }
  }

  private static FolderData folder(String name, int position) {
    return new FolderData(
        name, position, "/library/" + name, SortType.DEFAULT, FilterType.OR, FolderStatus.NONE);
  }

  private static Movie movie(String title) {
    return Movie.builder().title(title).path(Path.of("/library", title)).file("movie.mkv").build();
  }

  // ---- search predicate -------------------------------------------------

  @Test
  void blankSearchKeyMatchesEverything() {
    assertTrue(matches("John Wick", ""));
    assertTrue(matches("John Wick", "   "));
  }

  @Test
  void matchesCaseInsensitiveSubstring() {
    assertTrue(matches("John Wick", "jo"));
    assertTrue(matches("John Wick", "JO"));
    assertTrue(matches("John Wick", " Jo"));
    assertTrue(matches("John Wick: Chapter 2", "chapter"));
  }

  @Test
  void rejectsNonMatchingKey() {
    assertFalse(matches("John Wick", "zzzzz"));
  }

  private boolean matches(String title, String searchKey) {
    viewModel.searchKeyProperty().set(searchKey);
    Predicate<? super Media> predicate =
        ((FilteredList<Media>) viewModel.visibleMedia()).getPredicate();
    return predicate.test(movie(title));
  }

  // ---- folder selection -------------------------------------------------

  @Test
  void selectFolderClearsSearchKey() {
    viewModel.searchKeyProperty().set("john");
    viewModel.selectFolder(2);
    assertEquals("", viewModel.searchKeyProperty().get());
  }

  @Test
  void selectingNewFolderLoadsItsTagsAndData() {
    viewModel.selectFolder(2);
    verify(service).mediaTags(2);
    verify(service).folderData(2);
  }

  @Test
  void reselectingCurrentFolderReloadsIt() {
    viewModel.selectFolder(0); // 0 is already selected
    verify(service).mediaTags(0);
    verify(service).folderData(0);
  }

  @Test
  void folderLoadAppliesDataAndMedia() throws Exception {
    when(service.folderData(0)).thenReturn(CompletableFuture.completedFuture(folder("Films", 0)));
    when(service.folderMedia(eq(0), any()))
        .thenReturn(CompletableFuture.completedFuture(List.of(movie("John Wick"), movie("Heat"))));

    viewModel.selectFolder(0);
    drainFx();

    assertEquals("Films", viewModel.currentFolderProperty().get().name());
    assertEquals(2, viewModel.visibleMedia().size());
    assertEquals("2 ITEMS", viewModel.statusTextProperty().get());
  }

  @Test
  void staleFolderLoadIsDiscarded() throws Exception {
    CompletableFuture<FolderData> slow = new CompletableFuture<>();
    when(service.folderData(0)).thenReturn(slow);

    viewModel.selectFolder(0); // starts loading folder 0
    viewModel.selectedFolderIdProperty().set(2); // user moved on
    slow.complete(folder("Films", 0)); // folder 0 answers late
    drainFx();

    assertNull(viewModel.currentFolderProperty().get(), "stale folder data must be dropped");
  }

  @Test
  void searchNarrowsVisibleMediaAndStatus() throws Exception {
    when(service.folderData(0)).thenReturn(CompletableFuture.completedFuture(folder("Films", 0)));
    when(service.folderMedia(eq(0), any()))
        .thenReturn(CompletableFuture.completedFuture(List.of(movie("John Wick"), movie("Heat"))));
    viewModel.selectFolder(0);
    drainFx();

    viewModel.searchKeyProperty().set("heat");

    assertEquals(1, viewModel.visibleMedia().size());
    assertEquals("1 ITEMS", viewModel.statusTextProperty().get());
  }

  // ---- tag filters ------------------------------------------------------

  @Test
  void modifyTagTogglesSelection() {
    FilterOption tag = new FilterOption("GENRE", "Action");
    viewModel.modifyTag(1, tag);
    assertTrue(viewModel.hasTag(1, tag));
    viewModel.modifyTag(1, tag);
    assertFalse(viewModel.hasTag(1, tag));
  }

  @Test
  void selectedTagsAreKeptPerFolder() {
    FilterOption tag = new FilterOption("GENRE", "Action");
    viewModel.modifyTag(1, tag);
    assertTrue(viewModel.selectedTags(2).isEmpty());
  }

  @Test
  void modifyTagOnCurrentFolderReloadsWithTags() {
    FilterOption tag = new FilterOption("GENRE", "Action");
    viewModel.modifyTag(0, tag);
    verify(service).folderMedia(0, List.of(tag));
  }

  @Test
  void modifyTagOnOtherFolderDoesNotReload() {
    viewModel.modifyTag(3, new FilterOption("GENRE", "Action"));
    verify(service, never()).folderMedia(anyInt(), any());
  }

  @Test
  void clearTagsWithoutSelectionDoesNothing() {
    viewModel.clearTags(0);
    verify(service, never()).folderMedia(anyInt(), any());
  }

  @Test
  void removeTagFolderForgetsSelection() {
    FilterOption tag = new FilterOption("GENRE", "Action");
    viewModel.modifyTag(5, tag);
    viewModel.removeTagFolder(5);
    assertTrue(viewModel.selectedTags(5).isEmpty());
  }

  // ---- skip folders / settings ------------------------------------------

  @Test
  void addSkipFolderAppendsToExisting() {
    viewModel.settingProperty().set(new Setting(true, List.of("extras"), ThemeMode.SYSTEM));
    viewModel.addSkipFolder("bonus");
    verify(service).updateSkipFolders(List.of("extras", "bonus"));
  }

  @Test
  void addSkipFolderIgnoresDuplicate() {
    viewModel.settingProperty().set(new Setting(true, List.of("extras"), ThemeMode.SYSTEM));
    viewModel.addSkipFolder("extras");
    verify(service, never()).updateSkipFolders(any());
  }

  @Test
  void removeSkipFolderDropsOnlyThatName() {
    viewModel
        .settingProperty()
        .set(new Setting(true, List.of("extras", "bonus"), ThemeMode.SYSTEM));
    viewModel.removeSkipFolder("bonus");
    verify(service).updateSkipFolders(List.of("extras"));
  }

  @Test
  void settingsMutationPublishesRefreshedSettings() throws Exception {
    Setting refreshed = new Setting(false, List.of(), ThemeMode.SYSTEM);
    when(service.setShowSidePanel(false)).thenReturn(CompletableFuture.completedFuture(null));
    when(service.settings()).thenReturn(CompletableFuture.completedFuture(refreshed));

    viewModel.setShowSidePanel(false);
    drainFx();

    assertEquals(refreshed, viewModel.settingProperty().get());
  }

  @Test
  void settingsMutationErrorIsNotified() {
    viewModel.settingProperty().set(new Setting(true, List.of(), ThemeMode.SYSTEM));
    when(service.updateSkipFolders(any()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

    viewModel.addSkipFolder("bonus");

    assertEquals(List.of("Skip Folder Error: boom"), notifications);
  }

  // ---- library build / delete -------------------------------------------

  @Test
  void buildCompletionNotifiesSuccess() throws Exception {
    when(service.build("Films", "/library/Films", 1))
        .thenReturn(CompletableFuture.completedFuture(null));

    viewModel.refreshLibrary("Films", "/library/Films", 1);
    drainFx();

    assertEquals(List.of("Building directory Films is finished."), notifications);
  }

  @Test
  void buildFailureNotifiesError() throws Exception {
    when(service.build("Films", "/library/Films", 1))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("disk gone")));

    viewModel.refreshLibrary("Films", "/library/Films", 1);
    drainFx();

    assertEquals(List.of("Update Library Error: disk gone"), notifications);
  }

  @Test
  void deleteFolderRefreshesListAndForgetsItsTags() throws Exception {
    FolderData films = folder("Films", 4);
    viewModel.modifyTag(4, new FilterOption("GENRE", "Action"));
    when(service.deleteFolder("Films")).thenReturn(CompletableFuture.completedFuture(null));
    when(service.folderList())
        .thenReturn(CompletableFuture.completedFuture(List.of(folder("Shows", 0))));

    viewModel.deleteFolder(films);
    drainFx();

    assertEquals(1, viewModel.folders().size());
    assertEquals("Shows", viewModel.folders().get(0).name());
    assertTrue(viewModel.selectedTags(4).isEmpty());
  }

  @Test
  void deleteFolderErrorIsNotified() throws Exception {
    when(service.deleteFolder("Films"))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("locked")));

    viewModel.deleteFolder(folder("Films", 4));
    drainFx();

    assertEquals(List.of("Update Folder Error: locked"), notifications);
  }

  // ---- opening media -----------------------------------------------------

  @Test
  void openMovieOpensItsFile() {
    viewModel.openMedia(movie("John Wick"));
    verify(hostServices).showDocument(Path.of("/library/John Wick/movie.mkv").toUri().toString());
  }

  @Test
  void openContainingFolderOpensTheFolder() {
    viewModel.openContainingFolder(movie("John Wick"));
    verify(hostServices).showDocument(Path.of("/library/John Wick").toUri().toString());
  }

  @Test
  void fetchEpisodesWithoutCurrentFolderReturnsEmpty() {
    AtomicReference<Map<String, List<model.Episode>>> result = new AtomicReference<>();
    viewModel.fetchEpisodes(
        model.TvShow.builder().title("Show").path(Path.of("/library/Show")).build(), result::set);
    assertEquals(Map.of(), result.get());
  }
}
