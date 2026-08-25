package ui.shell;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import ui.helper.Ui;

public final class TitleBar {

  private final HBox root;
  private final Stage stage;

  // Window geometry saved before maximizing; an undecorated stage does not restore it for us.
  private double restoreX;
  private double restoreY;
  private double restoreWidth;
  private double restoreHeight;

  // Cursor offset inside the window while a title-bar drag is in progress.
  private double dragOffsetX;
  private double dragOffsetY;

  public TitleBar(Stage stage, Runnable onToggleSidebar) {
    this.stage = stage;
    root = new HBox();
    root.getStyleClass().add("title-bar");
    root.setAlignment(Pos.CENTER_LEFT);
    root.setPadding(new Insets(0, 16, 0, 22));
    root.setMinHeight(54);
    root.setPrefHeight(54);

    VBox burgerLines = new VBox(4);
    burgerLines.setAlignment(Pos.CENTER_LEFT);
    Region l1 = new Region();
    l1.setMinSize(18, 1.5);
    l1.getStyleClass().add("burger-line");
    Region l2 = new Region();
    l2.setMinSize(12, 1.5);
    l2.getStyleClass().add("burger-line");
    burgerLines.getChildren().addAll(l1, l2);
    Button burger = Ui.button(burgerLines, "title-btn");
    burger.setPadding(new Insets(8));
    burger.setTooltip(new Tooltip("Toggle sidebar"));
    burger.setAccessibleText("Toggle sidebar");
    burger.setOnAction(e -> onToggleSidebar.run());

    var wordmark = Ui.label("VAULT", "wordmark");
    var dot = Ui.label(".", "accent-dot-wordmark");
    HBox brand = new HBox(0, wordmark, dot);
    brand.setAlignment(Pos.CENTER_LEFT);

    HBox left = new HBox(16, burger, brand);
    left.setAlignment(Pos.CENTER_LEFT);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);

    Node min =
        windowButton(Ui.ico("M2 7 L12 7", 1.2), "Minimize", () -> stage.setIconified(true), false);
    Node max =
        windowButton(
            Ui.ico("M3 3 L11 3 L11 11 L3 11 Z", 1.2), "Maximize", this::toggleMaximize, false);
    Node close = windowButton(Ui.ico("M3 3 L11 11 M11 3 L3 11", 1.2), "Close", stage::close, true);
    HBox controls = new HBox(4, min, max, close);
    controls.setAlignment(Pos.CENTER_RIGHT);

    root.getChildren().addAll(left, spacer, controls);

    // double click to maximize the window
    root.setOnMouseClicked(
        e -> {
          if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
            toggleMaximize();
          }
        });

    root.addEventFilter(
        MouseEvent.MOUSE_PRESSED,
        e -> {
          dragOffsetX = e.getScreenX() - stage.getX();
          dragOffsetY = e.getScreenY() - stage.getY();
        });

    // drag and move
    root.setOnMouseDragged(
        e -> {
          if (stage.isMaximized()) {
            // un-maximize and keep the window under the cursor
            double w = restoreWidth > 0 ? restoreWidth : stage.getWidth();
            stage.setMaximized(false);
            stage.setWidth(w);
            stage.setHeight(restoreHeight > 0 ? restoreHeight : stage.getHeight());
            dragOffsetX = Math.min(dragOffsetX, w - 80);
          }
          stage.setX(e.getScreenX() - dragOffsetX);
          stage.setY(e.getScreenY() - dragOffsetY);
        });
  }

  private void toggleMaximize() {
    if (stage.isMaximized()) {
      stage.setMaximized(false);
      if (restoreWidth > 0) {
        stage.setX(restoreX);
        stage.setY(restoreY);
        stage.setWidth(restoreWidth);
        stage.setHeight(restoreHeight);
      }
    } else {
      restoreX = stage.getX();
      restoreY = stage.getY();
      restoreWidth = stage.getWidth();
      restoreHeight = stage.getHeight();
      stage.setMaximized(true);
    }
  }

  private static Node windowButton(SVGPath icon, String name, Runnable action, boolean danger) {
    Button b = Ui.button(icon, danger ? "win-btn-close" : "win-btn");
    b.setPadding(Insets.EMPTY);
    b.setMinSize(28, 28);
    b.setTooltip(new Tooltip(name));
    b.setAccessibleText(name);
    b.setOnAction(_ -> action.run());
    return b;
  }

  public Region getNode() {
    return root;
  }
}
