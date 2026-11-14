package com.vault.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import enums.FilterType;
import enums.FolderStatus;
import enums.SortType;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import model.FolderData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import service.AppConfig;
import service.LibraryService;
import ui.ThemeManager;
import ui.shell.Sidebar;
import ui.shell.TitleBar;
import viewmodel.LibraryViewModel;

class ShellTest {

  private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

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
  void theSidebarMarksTheSelectedLibraryFromTheStartAndAfterAChange() throws Exception {
    try (LibraryService service = new LibraryService(new AppConfig(tempDir.resolve("data")))) {
      onFx(() -> {
        LibraryViewModel viewModel = new LibraryViewModel(service, message -> {}, null);
        Sidebar sidebar = new Sidebar(viewModel, () -> {}, () -> {}, () -> {});
        new Scene(sidebar.getNode(), 300, 600);
        viewModel.folders().setAll(folder("Films", 0), folder("Shows", 1));
        sidebar.getNode().applyCss();
        sidebar.getNode().layout();
        assertEquals(List.of("Films"), activeRows(sidebar.getNode()));

        viewModel.selectedFolderIdProperty().set(1);
        assertEquals(List.of("Shows"), activeRows(sidebar.getNode()));
        return null;
      });
    }
  }

  @Test
  void theThemeButtonNamesTheModeFromTheStartAndAfterAChange() throws Exception {
    try (LibraryService service = new LibraryService(new AppConfig(tempDir.resolve("data")))) {
      onFx(() -> {
        ThemeManager themes = new ThemeManager(service);
        TitleBar titleBar = new TitleBar(new Stage(), () -> {}, themes);
        assertEquals(List.of("Theme: system (click to change)"), themeButtonNames(titleBar));

        themes.cycle();
        assertEquals(List.of("Theme: light (click to change)"), themeButtonNames(titleBar));
        return null;
      });
    }
  }

  private static List<String> activeRows(Region sidebar) {
    return sidebar.lookupAll(".button").stream()
        .map(Button.class::cast)
        .filter(button -> button.getPseudoClassStates().contains(ACTIVE))
        .map(Button::getAccessibleText)
        .toList();
  }

  private static List<String> themeButtonNames(TitleBar titleBar) {
    return titleBar.getNode().lookupAll(".button").stream()
        .map(Button.class::cast)
        .map(Button::getAccessibleText)
        .filter(name -> name != null && name.startsWith("Theme:"))
        .toList();
  }

  private static FolderData folder(String name, int position) {
    return new FolderData(
        name, position, "/library/" + name, SortType.DEFAULT, FilterType.OR, FolderStatus.NONE);
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
