package ui.content;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.controlsfx.control.GridCell;
import org.controlsfx.control.GridView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GridViewScrollerTest {

  private static final int COLUMNS = 4;
  private static final int VISIBLE_ROWS = 3;

  @BeforeAll
  static void initToolkit() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException alreadyRunning) {
      // toolkit started by an earlier test class
    }
  }

  @Test
  void rowsOutsideTheViewportAreNeverBound() throws Exception {
    CompletableFuture<Integer> highestItemBound = new CompletableFuture<>();
    Platform.runLater(() -> {
      try {
        highestItemBound.complete(highestItemBoundAfterRepeatedLayouts());
      } catch (Throwable t) {
        highestItemBound.completeExceptionally(t);
      }
    });

    int highest = highestItemBound.get(20, TimeUnit.SECONDS);

    int limit = COLUMNS * (VISIBLE_ROWS + 2);
    assertTrue(
        highest < limit,
        "item "
            + highest
            + " was bound although only the first "
            + limit
            + " can be near the viewport");
  }

  private static int highestItemBoundAfterRepeatedLayouts() {
    TreeSet<Integer> bound = new TreeSet<>();
    GridView<Integer> grid =
        new GridView<>(
            FXCollections.observableArrayList(IntStream.range(0, 2000).boxed().toList()));
    grid.setCellWidth(100);
    grid.setCellHeight(150);
    grid.setHorizontalCellSpacing(5);
    grid.setVerticalCellSpacing(5);
    grid.setCellFactory(view ->
        new GridCell<>() {
          @Override
          protected void updateItem(Integer item, boolean empty) {
            super.updateItem(item, empty);
            if (!empty && item != null) {
              bound.add(item);
            }
          }
        });
    new GridViewScroller(grid);

    Scene scene = new Scene(grid, 500, VISIBLE_ROWS * 160);
    grid.applyCss();
    grid.layout();
    for (int pass = 0; pass < 40; pass++) {
      Node card = grid.lookup(".grid-cell");
      ((Parent) card).requestLayout();
      scene.getRoot().layout();
    }
    return bound.last();
  }
}
