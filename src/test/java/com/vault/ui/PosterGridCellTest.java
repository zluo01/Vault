package com.vault.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import model.Media;
import model.Movie;
import org.controlsfx.control.GridView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ui.card.CardActions;
import ui.card.PosterGridCell;

class PosterGridCellTest {

  private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

  private static final CardActions NO_ACTIONS =
      new CardActions() {
        @Override
        public void select(int index) {}

        @Override
        public void open(Media media, int index) {}

        @Override
        public void openContainingFolder(Media media) {}
      };

  private final IntegerProperty selectedIndex = new SimpleIntegerProperty(-1);
  private final DoubleProperty posterWidth = new SimpleDoubleProperty(200);
  private Scene shownIn;

  @BeforeAll
  static void initToolkit() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException alreadyRunning) {
      // toolkit started by an earlier test class
    }
  }

  @Test
  void cellsTheGridDroppedCanBeCollected() throws Exception {
    List<WeakReference<PosterGridCell>> dropped =
        onFx(() -> {
          VBox shown = new VBox();
          new Scene(shown);
          List<WeakReference<PosterGridCell>> refs = new ArrayList<>();
          for (int i = 0; i < 20; i++) {
            PosterGridCell cell = new PosterGridCell(selectedIndex, posterWidth, NO_ACTIONS);
            shown.getChildren().add(cell);
            refs.add(new WeakReference<>(cell));
          }
          shown.getChildren().clear();
          return refs;
        });

    long stillReachable = reachableAfterGc(dropped);

    assertTrue(
        stillReachable == 0,
        stillReachable + " of " + dropped.size() + " dropped cells are still reachable");
  }

  @Test
  void aLiveCellKeepsFollowingTheSelectionAfterGc() throws Exception {
    PosterGridCell cell =
        onFx(() -> {
          GridView<Media> grid =
              new GridView<>(FXCollections.observableArrayList(movie("Heat"), movie("John Wick")));
          PosterGridCell created = new PosterGridCell(selectedIndex, posterWidth, NO_ACTIONS);
          created.updateGridView(grid);
          created.updateIndex(1);
          shownIn = new Scene(new VBox(created));
          return created;
        });
    for (int i = 0; i < 5; i++) {
      System.gc();
      Thread.sleep(50);
    }

    boolean selected =
        onFx(() -> {
          selectedIndex.set(1);
          return poster(cell).getPseudoClassStates().contains(SELECTED);
        });
    boolean deselected =
        onFx(() -> {
          selectedIndex.set(0);
          return !poster(cell).getPseudoClassStates().contains(SELECTED);
        });

    assertTrue(selected, "the cell did not react to being selected");
    assertTrue(deselected, "the cell did not react to losing the selection");
    assertFalse(cell.isEmpty());
  }

  private static Node poster(PosterGridCell cell) {
    return cell.getGraphic().lookup(".poster");
  }

  private static Media movie(String title) {
    return Movie.builder()
        .title(title)
        .path(Path.of("/library", title))
        .file(title + ".mkv")
        .build();
  }

  private static long reachableAfterGc(List<WeakReference<PosterGridCell>> refs)
      throws InterruptedException {
    long reachable = refs.size();
    for (int attempt = 0; attempt < 40 && reachable > 0; attempt++) {
      System.gc();
      Thread.sleep(50);
      reachable = refs.stream().filter(ref -> ref.get() != null).count();
    }
    return reachable;
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
