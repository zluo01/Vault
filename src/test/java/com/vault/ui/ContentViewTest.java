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
