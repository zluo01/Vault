package ui;

import java.util.ArrayDeque;
import java.util.Deque;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import ui.helper.Ui;
import viewmodel.Notifier;

public final class Toast implements Notifier {

  private static final double HOLD_BASE_MS = 1500;
  private static final double HOLD_PER_EXTRA_CHAR_MS = 30;
  private static final int HOLD_FREE_CHARS = 40;
  private static final double HOLD_MAX_MS = 5000;

  private final Deque<String> pending = new ArrayDeque<>();
  private StackPane host;
  private boolean showing;

  /** Attach the root stack toasts are layered onto. Messages shown earlier are dropped. */
  public void attachTo(StackPane root) {
    this.host = root;
  }

  @Override
  public void show(String message) {
    if (Platform.isFxApplicationThread()) {
      enqueue(message);
    } else {
      Platform.runLater(() -> enqueue(message));
    }
  }

  private void enqueue(String message) {
    pending.add(message);
    if (!showing) {
      drain();
    }
  }

  private void drain() {
    String next = pending.poll();
    if (next == null) {
      showing = false;
      return;
    }
    showing = true;
    display(next);
  }

  /** Hold time grows with message length so multi-line errors stay readable. */
  private static Duration holdFor(String text) {
    double extra = Math.max(0, text.length() - HOLD_FREE_CHARS) * HOLD_PER_EXTRA_CHAR_MS;
    return Duration.millis(Math.min(HOLD_MAX_MS, HOLD_BASE_MS + extra));
  }

  private void display(String text) {
    if (host == null) {
      showing = false;
      pending.clear();
      return;
    }
    HBox toast = new HBox(11);
    toast.getStyleClass().add("toast");
    toast.setAlignment(Pos.CENTER_LEFT);
    toast.setPadding(new Insets(13, 22, 13, 22));
    toast.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    Region dot = new Region();
    dot.setMinSize(7, 7);
    dot.setMaxSize(7, 7);
    dot.getStyleClass().add("toast-dot");
    toast.getChildren().addAll(dot, Ui.label(text, "toast-label"));
    StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
    StackPane.setMargin(toast, new Insets(0, 0, 34, 0));

    host.getChildren().add(toast);
    FadeTransition in = new FadeTransition(Duration.millis(180), toast);
    in.setFromValue(0);
    in.setToValue(1);
    PauseTransition hold = new PauseTransition(holdFor(text));
    FadeTransition out = new FadeTransition(Duration.millis(260), toast);
    out.setFromValue(1);
    out.setToValue(0);
    SequentialTransition seq = new SequentialTransition(in, hold, out);
    seq.setOnFinished(
        e -> {
          host.getChildren().remove(toast);
          drain();
        });
    seq.play();
  }
}
