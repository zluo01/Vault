package ui.dialog;

import java.io.File;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import ui.MainView;
import ui.helper.Ui;

/** The card, header and Cancel / confirm footer every Vault dialog is built from. */
final class DialogCard {

  private final MainView host;
  private final VBox card = new VBox();

  DialogCard(MainView host, double width, String title, String subtitle) {
    this.host = host;
    card.getStyleClass().add("dialog");
    card.setMaxSize(width, Region.USE_PREF_SIZE);
    card.setMinWidth(width);
    card.setPadding(new Insets(30));

    Label sub = Ui.label(subtitle, "dialog-sub");
    sub.setWrapText(true);
    VBox head = new VBox(8, Ui.label(title, "dialog-title"), sub);
    VBox.setMargin(head, new Insets(0, 0, 26, 0));
    card.getChildren().add(head);
  }

  /** Add the form fields shown between the header and the footer. */
  DialogCard body(Node... fields) {
    card.getChildren().addAll(fields);
    return this;
  }

  /**
   * Add the footer and present the dialog.
   *
   * @param confirmLabel text of the primary button
   * @param onConfirm runs on confirm; returns false to keep the dialog open (validation failed)
   * @param focus node that takes the initial focus, or null to focus Cancel
   */
  void show(String confirmLabel, BooleanSupplier onConfirm, Node focus) {
    Button cancel = Ui.button("Cancel", "ghost-btn");
    cancel.setCancelButton(true);
    cancel.setOnAction(e -> host.closeDialog(card));
    Button confirm = Ui.button(confirmLabel, "primary-btn");
    confirm.setDefaultButton(true);
    confirm.setOnAction(e -> {
      if (onConfirm.getAsBoolean()) {
        host.closeDialog(card);
      }
    });
    Region sp = new Region();
    HBox.setHgrow(sp, Priority.ALWAYS);
    HBox footer = new HBox(12, sp, cancel, confirm);
    footer.setAlignment(Pos.CENTER_RIGHT);
    if (card.getChildren().size() > 1) {
      // a form sits between the header and the footer
      VBox.setMargin(footer, new Insets(30, 0, 0, 0));
    }
    card.getChildren().add(footer);

    host.showDialog(card);
    (focus == null ? cancel : focus).requestFocus();
  }

  static TextField input(String text, String prompt) {
    TextField field = new TextField(text);
    field.setPromptText(prompt);
    field.getStyleClass().add("dialog-input");
    return field;
  }

  /**
   * The "Folder Path" block: {@code path} plus a Browse button that fills it from a directory
   * chooser. {@code onBrowsed} sees every directory picked that way.
   */
  Node folderPath(TextField path, Consumer<File> onBrowsed) {
    HBox.setHgrow(path, Priority.ALWAYS);
    Button browse = Ui.button("Browse", "browse-btn");
    browse.setOnAction(e -> {
      DirectoryChooser chooser = new DirectoryChooser();
      chooser.setTitle("Select library folder");
      File dir = chooser.showDialog(host.window());
      if (dir != null) {
        path.setText(dir.getAbsolutePath());
        onBrowsed.accept(dir);
      }
    });
    HBox row = new HBox(10, path, browse);
    row.setAlignment(Pos.CENTER_LEFT);
    VBox block = Ui.fieldBlock("Folder Path", row);
    VBox.setMargin(block, new Insets(18, 0, 0, 0));
    return block;
  }
}
