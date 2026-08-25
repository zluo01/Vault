package ui.dialog;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import model.FolderData;
import ui.MainView;
import ui.Toast;
import ui.helper.Ui;
import viewmodel.LibraryViewModel;

public final class ImportDialog {

  private ImportDialog() {}

  public static void show(MainView host, LibraryViewModel viewModel, Toast toast) {
    VBox card = new VBox();
    card.getStyleClass().add("dialog");
    card.setMaxSize(460, Region.USE_PREF_SIZE);
    card.setMinWidth(460);
    card.setPadding(new Insets(30));

    Label title = Ui.label("Import Library", "dialog-title");
    Label sub = Ui.label("Add new folder to manage.", "dialog-sub");
    sub.setWrapText(true);
    VBox head = new VBox(8, title, sub);
    VBox.setMargin(head, new Insets(0, 0, 26, 0));

    TextField name = new TextField();
    name.setPromptText("e.g. Sci-Fi Collection");
    name.getStyleClass().add("dialog-input");
    VBox nameBlock = Ui.fieldBlock("Library Name", name);

    TextField path = new TextField();
    path.setPromptText("Select a folder…");
    path.getStyleClass().add("dialog-input");
    HBox.setHgrow(path, Priority.ALWAYS);
    Button browse = Ui.button("Browse", "browse-btn");
    browse.setOnAction(
        e -> {
          DirectoryChooser dc = new DirectoryChooser();
          dc.setTitle("Select library folder");
          File dir = dc.showDialog(host.window());
          if (dir != null) {
            path.setText(dir.getAbsolutePath());
            if (name.getText().isBlank()) {
              name.setText(dir.getName());
            }
          }
        });
    HBox pathRow = new HBox(10, path, browse);
    pathRow.setAlignment(Pos.CENTER_LEFT);
    VBox pathBlock = Ui.fieldBlock("Folder Path", pathRow);
    VBox.setMargin(pathBlock, new Insets(18, 0, 0, 0));

    Button cancel = Ui.button("Cancel", "ghost-btn");
    cancel.setCancelButton(true);
    cancel.setOnAction(e -> host.closeDialog(card));
    Button confirm = Ui.button("Import Library", "primary-btn");
    confirm.setDefaultButton(true);
    confirm.setOnAction(
        e -> {
          String nm = name.getText().trim();
          String pth = path.getText().trim();
          if (nm.isEmpty() || pth.isEmpty()) {
            toast.show("Enter a name and choose a folder");
            return;
          }
          if (viewModel.folders().stream().map(FolderData::name).anyMatch(nm::equals)) {
            toast.show("A library named " + nm + " already exists");
            return;
          }
          if (viewModel.folders().stream().map(FolderData::path).anyMatch(pth::equals)) {
            toast.show("That folder is already imported");
            return;
          }
          if (!Files.isDirectory(Path.of(pth))) {
            toast.show("Path is not a folder");
            return;
          }
          host.closeDialog(card);
          viewModel.addLibrary(nm, pth);
          toast.show("Importing · " + nm);
        });
    Region sp = new Region();
    HBox.setHgrow(sp, Priority.ALWAYS);
    HBox footer = new HBox(12, sp, cancel, confirm);
    footer.setAlignment(Pos.CENTER_RIGHT);
    VBox.setMargin(footer, new Insets(30, 0, 0, 0));

    card.getChildren().addAll(head, nameBlock, pathBlock, footer);
    host.showDialog(card);
    name.requestFocus();
  }
}
