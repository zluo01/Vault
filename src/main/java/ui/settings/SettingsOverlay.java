package ui.settings;

import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import model.FolderData;
import model.Setting;
import ui.MainView;
import ui.Toast;
import ui.dialog.ConfirmDialog;
import ui.dialog.EditFolderDialog;
import ui.dialog.SkipFolderDialog;
import ui.helper.Ui;
import viewmodel.LibraryViewModel;

public final class SettingsOverlay {

  private final LibraryViewModel viewModel;
  private final MainView host;
  private final Toast toast;

  private final VBox overlay = new VBox();
  private final VBox folderList = new VBox();
  private final VBox skipFolderList = new VBox();

  private final ListChangeListener<FolderData> foldersListener;
  private final ChangeListener<Setting> settingListener;

  public SettingsOverlay(final LibraryViewModel viewModel, final MainView host, final Toast toast) {
    this.viewModel = viewModel;
    this.host = host;
    this.toast = toast;

    build();

    foldersListener = _ -> rebuildFolders();
    settingListener = (_, _, _) -> rebuildSkipFolders();
    viewModel.folders().addListener(foldersListener);
    viewModel.settingProperty().addListener(settingListener);

    rebuildFolders();
    rebuildSkipFolders();
  }

  public Region getNode() {
    return overlay;
  }

  public void dispose() {
    viewModel.folders().removeListener(foldersListener);
    viewModel.settingProperty().removeListener(settingListener);
  }

  private void build() {
    overlay.getStyleClass().add("series");

    HBox backContent =
        new HBox(9, Ui.ico("M9 3 L4 8 L9 13", 1.4), Ui.label("LIBRARY", "back-label"));
    backContent.setAlignment(Pos.CENTER_LEFT);
    Button backRow = Ui.button(backContent, "back");
    backRow.setPadding(new Insets(26, 44, 4, 44));
    backRow.setMaxWidth(Region.USE_PREF_SIZE);
    backRow.setAccessibleText("Back to library");
    backRow.setOnAction(e -> host.closeSettings());

    VBox content = new VBox();
    content.setPadding(new Insets(20, 44, 56, 44));
    content.setMaxWidth(860);

    content.getChildren().add(Ui.label("SYSTEM", "kicker-sub"));
    Label st = new Label("Settings");
    st.getStyleClass().add("series-title");
    Label sdot = Ui.label(".", "accent-dot-series");
    HBox titleRow = new HBox(0, st, sdot);
    VBox.setMargin(titleRow, new Insets(12, 0, 8, 0));
    content.getChildren().add(titleRow);

    content.getChildren().add(sectionHeader("Libraries"));
    content.getChildren().add(folderList);
    content
        .getChildren()
        .add(
            settingRow(
                "Import a folder",
                "Add a top-level directory as a new library",
                actionButton("Import", host::showImportDialog)));

    content.getChildren().add(sectionHeader("Scanning"));
    content.getChildren().add(skipFolderList);
    content
        .getChildren()
        .add(
            settingRow(
                "Skip folders",
                "Folder names ignored while building a library",
                actionButton("Add", () -> SkipFolderDialog.show(host, viewModel, toast))));

    content.getChildren().add(sectionHeader("About"));
    content.getChildren().add(infoRow("Version", Ui.label(appVersion(), "info-val")));

    // center the width-capped settings column within the scroll viewport
    StackPane contentWrap = new StackPane(content);
    StackPane.setAlignment(content, Pos.TOP_CENTER);
    ScrollPane scroll = new ScrollPane(contentWrap);
    scroll.setFitToWidth(true);
    scroll.getStyleClass().add("grid-scroll");
    VBox.setVgrow(scroll, Priority.ALWAYS);

    overlay.getChildren().addAll(backRow, scroll);
  }

  private void rebuildFolders() {
    folderList.getChildren().clear();
    for (FolderData folder : viewModel.folders()) {
      Label name = Ui.label(folder.name(), "setting-title");
      Label path = Ui.label(folder.path(), "setting-sub");
      VBox txt = new VBox(4, name, path);
      txt.setMinWidth(0);
      HBox.setHgrow(txt, Priority.ALWAYS);

      Button edit =
          actionButton("Edit", () -> EditFolderDialog.show(host, viewModel, toast, folder));
      Button remove =
          ghostButton(
              "Remove",
              () ->
                  ConfirmDialog.show(
                      host,
                      "Remove Library",
                      "Remove \""
                          + folder.name()
                          + "\" from your libraries? The folder and its files stay on disk.",
                      "Remove",
                      () -> {
                        viewModel.deleteFolder(folder);
                        toast.show("Removed · " + folder.name());
                      }));

      HBox row = new HBox(12, txt, edit, remove);
      row.getStyleClass().add("setting-row");
      row.setAlignment(Pos.CENTER_LEFT);
      row.setPadding(new Insets(18, 2, 18, 2));
      folderList.getChildren().add(row);
    }
  }

  private void rebuildSkipFolders() {
    skipFolderList.getChildren().clear();
    Setting setting = viewModel.settingProperty().get();
    if (setting == null) {
      return;
    }
    for (String name : setting.skipFolders()) {
      Label label = Ui.label(name, "setting-title");
      Region sp = new Region();
      HBox.setHgrow(sp, Priority.ALWAYS);
      Button remove = ghostButton("Remove", () -> viewModel.removeSkipFolder(name));

      HBox row = new HBox(12, label, sp, remove);
      row.getStyleClass().add("setting-row");
      row.setAlignment(Pos.CENTER_LEFT);
      row.setPadding(new Insets(18, 2, 18, 2));
      skipFolderList.getChildren().add(row);
    }
  }

  private static String appVersion() {
    String version = SettingsOverlay.class.getPackage().getImplementationVersion();
    return version == null ? "dev" : "v" + version;
  }

  private Label sectionHeader(String text) {
    Label l = Ui.label(text.toUpperCase(), "section-header");
    VBox.setMargin(l, new Insets(36, 0, 6, 0));
    return l;
  }

  private Node settingRow(String title, String subtitle, Node control) {
    VBox txt = new VBox(4);
    txt.getChildren().addAll(Ui.label(title, "setting-title"), Ui.label(subtitle, "setting-sub"));
    HBox.setHgrow(txt, Priority.ALWAYS);
    HBox row = new HBox(20, txt, control);
    row.getStyleClass().add("setting-row");
    row.setAlignment(Pos.CENTER_LEFT);
    row.setPadding(new Insets(18, 2, 18, 2));
    return row;
  }

  private Node infoRow(String key, Label value) {
    Label k = Ui.label(key, "setting-title");
    Region sp = new Region();
    HBox.setHgrow(sp, Priority.ALWAYS);
    HBox row = new HBox(20, k, sp, value);
    row.getStyleClass().add("setting-row");
    row.setAlignment(Pos.CENTER_LEFT);
    row.setPadding(new Insets(18, 2, 18, 2));
    return row;
  }

  private static Button actionButton(String text, Runnable action) {
    Button button = Ui.button(text, "browse-btn");
    button.setOnAction(e -> action.run());
    return button;
  }

  private static Button ghostButton(String text, Runnable action) {
    Button button = Ui.button(text, "ghost-btn");
    button.setOnAction(e -> action.run());
    return button;
  }
}
