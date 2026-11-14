package com.vault.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import enums.FilterType;
import enums.FolderStatus;
import enums.SortType;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import model.FilterOption;
import model.FolderData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import service.AppConfig;
import service.LibraryService;
import ui.ThemeManager;
import ui.Toast;
import ui.content.ContentView;
import viewmodel.LibraryViewModel;

class ContentViewTest {

  @TempDir Path tempDir;

  @BeforeAll
  static void initToolkit() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException alreadyRunning) {
      // toolkit started by an earlier test class
    }
  }

  @Test
  void filterAndSortButtonsAreDisabledWhileTheFolderIsScanning() throws Exception {
    try (LibraryService service = new LibraryService(new AppConfig(tempDir.resolve("data")))) {
      onFx(() -> {
        LibraryViewModel viewModel = new LibraryViewModel(service, message -> {}, null);
        ContentView content =
            new ContentView(viewModel, null, new Toast(), new ThemeManager(service));
        new Scene(content.getNode());
        List<Button> buttons =
            content.getNode().lookupAll(".facet").stream().map(Button.class::cast).toList();
        assertEquals(6, buttons.size(), "four tag filters, the match toggle and the sort button");

        viewModel.currentFolderProperty().set(folder(FolderStatus.LOADING));
        for (Button button : buttons) {
          assertTrue(button.isDisabled(), button.getAccessibleText() + " is enabled during a scan");
        }

        viewModel.currentFolderProperty().set(folder(FolderStatus.NONE));
        for (Button button : buttons) {
          if (!button.getAccessibleText().startsWith("Filter by")) {
            assertFalse(button.isDisabled(), button.getAccessibleText() + " stayed disabled");
          }
        }
        return null;
      });
    }
  }

  @Test
  void theHeaderShowsTheCurrentFolderAndItsDefaultsWithoutOne() throws Exception {
    try (LibraryService service = new LibraryService(new AppConfig(tempDir.resolve("data")))) {
      onFx(() -> {
        LibraryViewModel viewModel = new LibraryViewModel(service, message -> {}, null);
        ContentView content =
            new ContentView(viewModel, null, new Toast(), new ThemeManager(service));
        new Scene(content.getNode());

        viewModel
            .currentFolderProperty()
            .set(
                new FolderData(
                    "Films", 6, "/x", SortType.TITLE_ASC, FilterType.AND, FolderStatus.NONE));
        assertEquals(
            List.of("Films", "07", SortType.TITLE_ASC.label(), "AND"), headerTexts(content));

        viewModel.currentFolderProperty().set(null);
        assertEquals(List.of("", "00", SortType.DEFAULT.label(), "OR"), headerTexts(content));
        return null;
      });
    }
  }

  @Test
  void aFilterBadgeCountsTheSelectedTagsOfItsCategory() throws Exception {
    try (LibraryService service = new LibraryService(new AppConfig(tempDir.resolve("data")))) {
      onFx(() -> {
        LibraryViewModel viewModel = new LibraryViewModel(service, message -> {}, null);
        ContentView content =
            new ContentView(viewModel, null, new Toast(), new ThemeManager(service));
        new Scene(content.getNode());
        Label badge =
            content.getNode().lookupAll(".facet").stream()
                .map(Button.class::cast)
                .filter(button -> "Filter by Genre".equals(button.getAccessibleText()))
                .map(button -> (Label) button.getGraphic().lookup(".facet-badge"))
                .findFirst()
                .orElseThrow();
        assertFalse(badge.isVisible());

        viewModel.modifyTag(0, new FilterOption("GENRE", "Action"));
        viewModel.modifyTag(0, new FilterOption("GENRE", "Drama"));
        viewModel.modifyTag(0, new FilterOption("ACTOR", "Someone"));
        assertEquals("2", badge.getText());
        assertTrue(badge.isVisible());

        System.gc();
        viewModel.modifyTag(0, new FilterOption("GENRE", "Action"));
        assertEquals("1", badge.getText());

        viewModel.clearTags(0);
        assertFalse(badge.isVisible());
        return null;
      });
    }
  }

  private static List<String> headerTexts(ContentView content) {
    List<Label> values =
        content.getNode().lookupAll(".facet").stream()
            .map(Button.class::cast)
            .filter(button -> !button.getAccessibleText().startsWith("Filter by"))
            .map(button -> (Label) button.getGraphic().lookup(".facet-text"))
            .toList();
    return List.of(
        ((Label) content.getNode().lookup(".h1")).getText(),
        ((Label) content.getNode().lookup(".kicker")).getText(),
        values.get(1).getText(),
        values.get(0).getText());
  }

  private static FolderData folder(FolderStatus status) {
    return new FolderData("Films", 0, "/library/Films", SortType.DEFAULT, FilterType.OR, status);
  }

  private static <T> T onFx(Callable<T> work) throws Exception {
    CompletableFuture<T> result = new CompletableFuture<>();
    Platform.runLater(() -> {
      try {
        result.complete(work.call());
      } catch (Throwable t) {
        result.completeExceptionally(t);
      }
    });
    return result.get(20, TimeUnit.SECONDS);
  }
}
