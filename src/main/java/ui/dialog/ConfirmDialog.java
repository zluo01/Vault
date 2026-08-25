package ui.dialog;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import ui.MainView;
import ui.helper.Ui;

public final class ConfirmDialog {

  private ConfirmDialog() {}

  public static void show(
      MainView host, String title, String message, String confirmLabel, Runnable onConfirm) {
    VBox card = new VBox();
    card.getStyleClass().add("dialog");
    card.setMaxSize(400, Region.USE_PREF_SIZE);
    card.setMinWidth(400);
    card.setPadding(new Insets(30));

    Label t = Ui.label(title, "dialog-title");
    Label sub = Ui.label(message, "dialog-sub");
    sub.setWrapText(true);
    VBox head = new VBox(8, t, sub);
    VBox.setMargin(head, new Insets(0, 0, 26, 0));

    Button cancel = Ui.button("Cancel", "ghost-btn");
    cancel.setCancelButton(true);
    cancel.setOnAction(e -> host.closeDialog(card));
    Button confirm = Ui.button(confirmLabel, "primary-btn");
    confirm.setDefaultButton(true);
    confirm.setOnAction(
        _ -> {
          host.closeDialog(card);
          onConfirm.run();
        });
    Region sp = new Region();
    HBox.setHgrow(sp, Priority.ALWAYS);
    HBox footer = new HBox(12, sp, cancel, confirm);
    footer.setAlignment(Pos.CENTER_RIGHT);

    card.getChildren().addAll(head, footer);
    host.showDialog(card);
    cancel.requestFocus();
  }
}
