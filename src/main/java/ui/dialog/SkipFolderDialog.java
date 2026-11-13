package ui.dialog;

import java.util.List;
import javafx.scene.control.TextField;
import model.Setting;
import ui.MainView;
import ui.Toast;
import ui.helper.Ui;
import viewmodel.LibraryViewModel;

/** Vault-style dialog to add a folder name that is skipped while scanning libraries. */
public final class SkipFolderDialog {

  private SkipFolderDialog() {}

  public static void show(MainView host, LibraryViewModel viewModel, Toast toast) {
    TextField name = DialogCard.input("", "e.g. extras");

    new DialogCard(
            host, 400, "Add Skip Folder", "Folders with this exact name are ignored during scans.")
        .body(Ui.fieldBlock("Folder Name", name))
        .show(
            "Add",
            () -> {
              String nm = name.getText().trim();
              Setting setting = viewModel.settingProperty().get();
              List<String> skipFolders = setting == null ? List.of() : setting.skipFolders();
              if (nm.isEmpty()) {
                toast.show("Enter a folder name");
                return false;
              }
              if (skipFolders.contains(nm)) {
                toast.show(nm + " is already skipped");
                return false;
              }
              viewModel.addSkipFolder(nm);
              return true;
            },
            name);
  }
}
