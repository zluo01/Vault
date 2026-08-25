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
import ui.ThemeManager;
import ui.helper.Ui;

public final class TitleBar {

  private static final String ICO_THEME_SYSTEM =
      "M2 3 L12 3 L12 9.5 L2 9.5 Z M5.5 12.5 L8.5 12.5 M7 9.5 L7 12.5";
  private static final String ICO_THEME_LIGHT =
      "M7 4.6 A2.4 2.4 0 1 0 7.001 4.6 M7 1.2 L7 2.6 M7 11.4 L7 12.8 M1.2 7 L2.6 7 "
          + "M11.4 7 L12.8 7 M2.9 2.9 L3.9 3.9 M10.1 10.1 L11.1 11.1 M11.1 2.9 L10.1 3.9 "
          + "M3.9 10.1 L2.9 11.1";
  private static final String ICO_THEME_DARK = "M9.6 2.4 A5 5 0 1 0 11.6 8.6 A4 4 0 0 1 9.6 2.4";

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

  public TitleBar(Stage stage, Runnable onToggleSidebar, ThemeManager themes) {
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
    HBox controls = new HBox(4, themeButton(themes), min, max, close);
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

  private static Node themeButton(ThemeManager themes) {
    SVGPath icon = Ui.ico(ICO_THEME_SYSTEM, 1.2);
    Button b = Ui.button(icon, "win-btn");
    b.setPadding(Insets.EMPTY);
    b.setMinSize(28, 28);
    b.setOnAction(_ -> themes.cycle());
    Tooltip tip = new Tooltip();
    b.setTooltip(tip);
    Runnable sync =
        () -> {
          var mode = themes.modeProperty().get();
          icon.setContent(
              switch (mode) {
                case SYSTEM -> ICO_THEME_SYSTEM;
                case LIGHT -> ICO_THEME_LIGHT;
                case DARK -> ICO_THEME_DARK;
              });
          String name = "Theme: " + mode.name().toLowerCase() + " (click to change)";
          tip.setText(name);
          b.setAccessibleText(name);
        };
    themes.modeProperty().addListener((_, _, _) -> sync.run());
    sync.run();
    return b;
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
