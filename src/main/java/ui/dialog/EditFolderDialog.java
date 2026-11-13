package ui.dialog;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.control.TextField;
import model.FolderData;
import ui.MainView;
import ui.Toast;
import ui.helper.Ui;
import viewmodel.LibraryViewModel;

public final class EditFolderDialog {

  private EditFolderDialog() {}

  public static void show(
      MainView host, LibraryViewModel viewModel, Toast toast, FolderData folder) {
    DialogCard dialog = new DialogCard(host, 460, "Edit Library", "Change a new target folder.");

    TextField name = DialogCard.input(folder.name(), "");
    name.setDisable(true);
    TextField path = DialogCard.input(folder.path(), "Select a folder…");

    dialog
        .body(Ui.fieldBlock("Library Name", name), dialog.folderPath(path, dir -> {}))
        .show(
            "Save",
            () -> {
              String pth = path.getText().trim();
              boolean usedByOther =
                  viewModel.folders().stream()
                      .anyMatch(f -> f.position() != folder.position() && f.path().equals(pth));
              if (pth.isEmpty() || !Files.isDirectory(Path.of(pth))) {
                toast.show("Path is not a folder");
                return false;
              }
              if (usedByOther) {
                toast.show("That folder is already imported");
                return false;
              }
              viewModel.changeFolderPath(folder.name(), folder.position(), pth);
              toast.show("Rescanning · " + folder.name());
              return true;
            },
            path);
  }
}
