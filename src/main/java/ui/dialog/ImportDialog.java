package ui.dialog;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.control.TextField;
import model.FolderData;
import ui.MainView;
import ui.Toast;
import ui.helper.Ui;
import viewmodel.LibraryViewModel;

public final class ImportDialog {

  private ImportDialog() {}

  public static void show(MainView host, LibraryViewModel viewModel, Toast toast) {
    DialogCard dialog = new DialogCard(host, 460, "Import Library", "Add new folder to manage.");

    TextField name = DialogCard.input("", "e.g. Sci-Fi Collection");
    TextField path = DialogCard.input("", "Select a folder…");

    dialog
        .body(
            Ui.fieldBlock("Library Name", name),
            dialog.folderPath(
                path,
                dir -> {
                  if (name.getText().isBlank()) {
                    name.setText(dir.getName());
                  }
                }))
        .show(
            "Import Library",
            () -> {
              String nm = name.getText().trim();
              String pth = path.getText().trim();
              if (nm.isEmpty() || pth.isEmpty()) {
                toast.show("Enter a name and choose a folder");
                return false;
              }
              if (viewModel.folders().stream().map(FolderData::name).anyMatch(nm::equals)) {
                toast.show("A library named " + nm + " already exists");
                return false;
              }
              if (viewModel.folders().stream().map(FolderData::path).anyMatch(pth::equals)) {
                toast.show("That folder is already imported");
                return false;
              }
              if (!Files.isDirectory(Path.of(pth))) {
                toast.show("Path is not a folder");
                return false;
              }
              viewModel.addLibrary(nm, pth);
              toast.show("Importing · " + nm);
              return true;
            },
            name);
  }
}
