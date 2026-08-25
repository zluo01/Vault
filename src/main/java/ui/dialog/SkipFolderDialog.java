package ui.dialog;

import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import model.Setting;
import ui.MainView;
import ui.Toast;
import ui.helper.Ui;
import viewmodel.LibraryViewModel;

/** Vault-style dialog to add a folder name that is skipped while scanning libraries. */
public final class SkipFolderDialog {

  private SkipFolderDialog() {}

  public static void show(MainView host, LibraryViewModel viewModel, Toast toast) {
    VBox card = new VBox();
    card.getStyleClass().add("dialog");
    card.setMaxSize(400, Region.USE_PREF_SIZE);
    card.setMinWidth(400);
    card.setPadding(new Insets(30));

    Label title = Ui.label("Add Skip Folder", "dialog-title");
    Label sub = Ui.label("Folders with this exact name are ignored during scans.", "dialog-sub");
    sub.setWrapText(true);
    VBox head = new VBox(8, title, sub);
    VBox.setMargin(head, new Insets(0, 0, 26, 0));

    TextField name = new TextField();
    name.setPromptText("e.g. extras");
    name.getStyleClass().add("dialog-input");
    VBox nameBlock = Ui.fieldBlock("Folder Name", name);

    Button cancel = Ui.button("Cancel", "ghost-btn");
    cancel.setCancelButton(true);
    cancel.setOnAction(e -> host.closeDialog(card));
    Button add = Ui.button("Add", "primary-btn");
    add.setDefaultButton(true);
    add.setOnAction(
        e -> {
          String nm = name.getText().trim();
          Setting setting = viewModel.settingProperty().get();
          List<String> skipFolders = setting == null ? List.of() : setting.skipFolders();
          if (nm.isEmpty()) {
            toast.show("Enter a folder name");
            return;
          }
          if (skipFolders.contains(nm)) {
            toast.show(nm + " is already skipped");
            return;
          }
          host.closeDialog(card);
          viewModel.addSkipFolder(nm);
        });
    Region sp = new Region();
    HBox.setHgrow(sp, Priority.ALWAYS);
    HBox footer = new HBox(12, sp, cancel, add);
    footer.setAlignment(Pos.CENTER_RIGHT);
    VBox.setMargin(footer, new Insets(30, 0, 0, 0));

    card.getChildren().addAll(head, nameBlock, footer);
    host.showDialog(card);
    name.requestFocus();
  }
}
