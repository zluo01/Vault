package ui.content;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.skin.VirtualFlow;
import org.controlsfx.control.GridView;

final class GridViewScroller {

  private static final double SCROLL_MARGIN = 8;

  private final GridView<?> grid;

  GridViewScroller(GridView<?> grid) {
    this.grid = grid;
  }

  void scrollToTop() {
    Platform.runLater(
        () -> {
          VirtualFlow<?> flow = virtualFlow();
          if (flow != null) {
            flow.scrollTo(0);
          }
        });
  }

  /** Scroll the given row into the viewport, keeping a small margin in the move direction. */
  void scrollRowIntoView(int row, int direction) {
    Platform.runLater(() -> scrollRowIntoViewNow(row, direction, true));
  }

  private void scrollRowIntoViewNow(int row, int direction, boolean verifyNextPulse) {
    VirtualFlow<?> flow = virtualFlow();
    if (flow == null) {
      return;
    }
    flow.layout();

    IndexedCell<?> target = flow.getVisibleCell(row);
    if (target == null) {
      int topRow = direction > 0 ? Math.max(0, row - visibleRows(flow) + 1) : row;
      flow.scrollToTop(Math.max(0, topRow));
      if (verifyNextPulse) {
        Platform.runLater(() -> scrollRowIntoViewNow(row, direction, false));
      }
      return;
    }

    double delta = scrollDelta(flow, target, direction);
    if (Math.abs(delta) > 0.5) {
      flow.scrollPixels(delta);
      if (verifyNextPulse) {
        Platform.runLater(() -> scrollRowIntoViewNow(row, direction, false));
      }
    }
  }

  private int visibleRows(VirtualFlow<?> flow) {
    return Math.max(1, (int) Math.floor(flow.getHeight() / Math.max(1, grid.getCellHeight())));
  }

  private double scrollDelta(VirtualFlow<?> flow, IndexedCell<?> target, int direction) {
    Bounds viewport = viewportBounds(flow);
    Bounds cell = target.localToScene(target.getBoundsInLocal());
    double margin =
        cell.getHeight() + SCROLL_MARGIN * 2 <= viewport.getHeight() ? SCROLL_MARGIN : 0;
    double topOverflow = viewport.getMinY() + margin - cell.getMinY();
    double bottomOverflow = cell.getMaxY() - (viewport.getMaxY() - margin);

    if (cell.getHeight() + margin * 2 > viewport.getHeight()) {
      if (direction > 0 && bottomOverflow > 0) {
        return bottomOverflow;
      }
      if (topOverflow > 0) {
        return -topOverflow;
      }
      return bottomOverflow > 0 ? bottomOverflow : 0;
    }
    if (topOverflow > 0) {
      return -topOverflow;
    }
    return bottomOverflow > 0 ? bottomOverflow : 0;
  }

  private Bounds viewportBounds(VirtualFlow<?> flow) {
    Node clippedContainer = flow.lookup(".clipped-container");
    Node viewport = clippedContainer == null ? flow : clippedContainer;
    return viewport.localToScene(viewport.getBoundsInLocal());
  }

  private VirtualFlow<?> virtualFlow() {
    grid.applyCss();
    Node node = grid.lookup(".virtual-flow");
    return node instanceof VirtualFlow<?> flow ? flow : null;
  }
}
