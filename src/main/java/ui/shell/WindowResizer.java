package ui.shell;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

public final class WindowResizer {

  private static final double MARGIN = 6;

  private final Stage stage;
  private final Scene scene;

  private Cursor edge; // non-null while the pointer is on a resize edge
  private boolean resizing;
  private double pressScreenX;
  private double pressScreenY;
  private double startX;
  private double startY;
  private double startWidth;
  private double startHeight;

  public static void install(Stage stage, Scene scene) {
    WindowResizer resizer = new WindowResizer(stage, scene);
    scene.addEventFilter(MouseEvent.MOUSE_MOVED, resizer::onMoved);
    scene.addEventFilter(MouseEvent.MOUSE_PRESSED, resizer::onPressed);
    scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, resizer::onDragged);
    scene.addEventFilter(MouseEvent.MOUSE_RELEASED, resizer::onReleased);
  }

  private WindowResizer(Stage stage, Scene scene) {
    this.stage = stage;
    this.scene = scene;
  }

  private void onMoved(MouseEvent e) {
    if (stage.isMaximized()) {
      clearEdge();
      return;
    }
    Cursor hit = edgeAt(e.getSceneX(), e.getSceneY());
    if (hit != edge) {
      edge = hit;
      scene.setCursor(hit == null ? Cursor.DEFAULT : hit);
    }
  }

  private void onPressed(MouseEvent e) {
    if (edge == null || stage.isMaximized()) {
      return;
    }
    resizing = true;
    pressScreenX = e.getScreenX();
    pressScreenY = e.getScreenY();
    startX = stage.getX();
    startY = stage.getY();
    startWidth = stage.getWidth();
    startHeight = stage.getHeight();
    e.consume();
  }

  private void onDragged(MouseEvent e) {
    if (!resizing || edge == null) {
      return;
    }
    double dx = e.getScreenX() - pressScreenX;
    double dy = e.getScreenY() - pressScreenY;

    if (edge == Cursor.E_RESIZE || edge == Cursor.NE_RESIZE || edge == Cursor.SE_RESIZE) {
      stage.setWidth(clampWidth(startWidth + dx));
    }
    if (edge == Cursor.S_RESIZE || edge == Cursor.SE_RESIZE || edge == Cursor.SW_RESIZE) {
      stage.setHeight(clampHeight(startHeight + dy));
    }
    if (edge == Cursor.W_RESIZE || edge == Cursor.NW_RESIZE || edge == Cursor.SW_RESIZE) {
      double width = clampWidth(startWidth - dx);
      stage.setX(startX + (startWidth - width));
      stage.setWidth(width);
    }
    if (edge == Cursor.N_RESIZE || edge == Cursor.NW_RESIZE || edge == Cursor.NE_RESIZE) {
      double height = clampHeight(startHeight - dy);
      stage.setY(startY + (startHeight - height));
      stage.setHeight(height);
    }
    e.consume();
  }

  private void onReleased(MouseEvent e) {
    if (resizing) {
      resizing = false;
      e.consume();
    }
  }

  private void clearEdge() {
    if (edge != null) {
      edge = null;
      scene.setCursor(Cursor.DEFAULT);
    }
  }

  private double clampWidth(double width) {
    return Math.max(stage.getMinWidth(), width);
  }

  private double clampHeight(double height) {
    return Math.max(stage.getMinHeight(), height);
  }

  private Cursor edgeAt(double x, double y) {
    boolean west = x < MARGIN;
    boolean east = x > scene.getWidth() - MARGIN;
    boolean north = y < MARGIN;
    boolean south = y > scene.getHeight() - MARGIN;
    if (north && west) {
      return Cursor.NW_RESIZE;
    }
    if (north && east) {
      return Cursor.NE_RESIZE;
    }
    if (south && west) {
      return Cursor.SW_RESIZE;
    }
    if (south && east) {
      return Cursor.SE_RESIZE;
    }
    if (north) {
      return Cursor.N_RESIZE;
    }
    if (south) {
      return Cursor.S_RESIZE;
    }
    if (west) {
      return Cursor.W_RESIZE;
    }
    if (east) {
      return Cursor.E_RESIZE;
    }
    return null;
  }
}
