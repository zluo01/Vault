package ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import model.TvShow;
import ui.content.ContentView;
import ui.content.SeriesOverlay;
import ui.dialog.ImportDialog;
import ui.settings.SettingsOverlay;
import ui.shell.Sidebar;
import ui.shell.TitleBar;
import viewmodel.LibraryViewModel;

public final class MainView {

  private final LibraryViewModel viewModel;
  private final Toast toast;

  private final StackPane root = new StackPane();
  private final StackPane centerStack;

  private Region seriesOverlay;
  private Region settingsOverlay;
  private Runnable settingsDispose;

  public MainView(LibraryViewModel viewModel, Stage stage, Toast toast) {
    this.viewModel = viewModel;
    this.toast = toast;

    final ContentView content = new ContentView(viewModel, this, toast);
    centerStack = new StackPane(content.getNode());

    final Sidebar sidebar =
        new Sidebar(viewModel, this::showImportDialog, this::showSettings, this::closeOverlays);
    TitleBar titleBar = new TitleBar(stage, sidebar::toggle);

    BorderPane base = new BorderPane();
    base.setTop(titleBar.getNode());
    base.setLeft(sidebar.getNode());
    base.setCenter(centerStack);

    root.getChildren().add(base);

    // ESC closes the topmost layer: dialog backdrop, then settings, then the series browser.
    root.addEventFilter(
        KeyEvent.KEY_PRESSED,
        e -> {
          if (e.getCode() != KeyCode.ESCAPE) {
            return;
          }
          if (closeTopDialog()) {
            e.consume();
          } else if (settingsOverlay != null) {
            closeSettings();
            e.consume();
          } else if (seriesOverlay != null) {
            closeSeries();
            e.consume();
          }
        });

    Platform.runLater(content::focusGrid);
  }

  /** Remove the most recently shown dialog backdrop, if any. */
  private boolean closeTopDialog() {
    for (int i = root.getChildren().size() - 1; i >= 0; i--) {
      if (root.getChildren().get(i).getStyleClass().contains("dialog-backdrop")) {
        root.getChildren().remove(i);
        return true;
      }
    }
    return false;
  }

  public StackPane getRoot() {
    return root;
  }

  public Window window() {
    return root.getScene() == null ? null : root.getScene().getWindow();
  }

  // --- full-screen overlays (series browser / settings) ----------------------------------------

  public void showSeries(TvShow show, int toneIndex) {
    closeSeries();
    seriesOverlay = new SeriesOverlay(viewModel, this, toast).build(show, toneIndex);
    animateOverlayIn(seriesOverlay);
  }

  public void closeSeries() {
    if (seriesOverlay != null) {
      animateOverlayOut(seriesOverlay);
      seriesOverlay = null;
    }
  }

  void showSettings() {
    if (settingsOverlay != null) {
      return;
    }
    SettingsOverlay overlay = new SettingsOverlay(viewModel, this, toast);
    settingsOverlay = overlay.getNode();
    settingsDispose = overlay::dispose;
    animateOverlayIn(settingsOverlay);
  }

  public void closeSettings() {
    if (settingsOverlay != null) {
      animateOverlayOut(settingsOverlay);
      settingsOverlay = null;
      if (settingsDispose != null) {
        settingsDispose.run();
        settingsDispose = null;
      }
    }
  }

  private void closeOverlays() {
    closeSeries();
    closeSettings();
  }

  /** Slide-up + fade entrance for full-screen overlays (series / settings). */
  private void animateOverlayIn(Region overlay) {
    centerStack.getChildren().add(overlay);
    overlay.setOpacity(0);
    overlay.setTranslateY(22);
    FadeTransition fade = new FadeTransition(Duration.millis(260), overlay);
    fade.setFromValue(0);
    fade.setToValue(1);
    fade.setInterpolator(Theme.EASE_OUT);
    TranslateTransition slide = new TranslateTransition(Duration.millis(340), overlay);
    slide.setFromY(22);
    slide.setToY(0);
    slide.setInterpolator(Theme.EASE_OUT);
    new ParallelTransition(fade, slide).play();
  }

  /** Fade + slide-down exit, then detach. */
  private void animateOverlayOut(Region overlay) {
    FadeTransition fade = new FadeTransition(Duration.millis(200), overlay);
    fade.setFromValue(1);
    fade.setToValue(0);
    fade.setInterpolator(Theme.EASE_OUT);
    TranslateTransition slide = new TranslateTransition(Duration.millis(240), overlay);
    slide.setFromY(0);
    slide.setToY(16);
    slide.setInterpolator(Theme.EASE_OUT);
    ParallelTransition pt = new ParallelTransition(fade, slide);
    pt.setOnFinished(e -> centerStack.getChildren().remove(overlay));
    pt.play();
  }

  // --- dialogs ----------------------------------------------------------------------------------

  public void showImportDialog() {
    ImportDialog.show(this, viewModel, toast);
  }

  /** Present a dialog card on a dimmed backdrop; clicking the backdrop dismisses it. */
  public void showDialog(Region card) {
    StackPane backdrop = new StackPane();
    backdrop.getStyleClass().add("dialog-backdrop");
    backdrop.setOnMouseClicked(e -> root.getChildren().remove(backdrop));
    card.setOnMouseClicked(javafx.event.Event::consume);
    StackPane.setAlignment(card, Pos.CENTER);
    backdrop.getChildren().add(card);
    root.getChildren().add(backdrop);

    FadeTransition ft = new FadeTransition(Duration.millis(160), backdrop);
    ft.setFromValue(0);
    ft.setToValue(1);
    ft.play();
  }

  public void closeDialog(Region card) {
    if (card.getParent() instanceof StackPane backdrop) {
      root.getChildren().remove(backdrop);
    }
  }
}
