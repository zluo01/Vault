package ui.shell;

import java.util.ArrayList;
import java.util.List;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.css.PseudoClass;
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
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import model.FolderData;
import model.FolderStats;
import model.Setting;
import ui.Theme;
import ui.helper.Ui;
import viewmodel.LibraryViewModel;

public final class Sidebar {

  private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

  private static final double EXPANDED = 286;
  private static final double COLLAPSED = 74;

  private static final String GEAR =
      "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 "
          + "0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 "
          + "1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 "
          + "1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l"
          + "-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V"
          + "3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 "
          + "2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h"
          + "-.09a1.65 1.65 0 0 0-1.51 1z";

  private final LibraryViewModel viewModel;
  private final Runnable onImport;
  private final Runnable onSettings;
  private final Runnable onSelect;

  private final StackPane holder = new StackPane();
  private VBox current;
  private VBox rowsBox;
  private Label countLabel;
  private boolean collapsed;
  private boolean settingSeen;

  public Sidebar(
      LibraryViewModel viewModel, Runnable onImport, Runnable onSettings, Runnable onSelect) {
    this.viewModel = viewModel;
    this.onImport = onImport;
    this.onSettings = onSettings;
    this.onSelect = onSelect;

    viewModel.folders().addListener((ListChangeListener<FolderData>) _ -> onFoldersChanged());
    viewModel
        .folderStats()
        .addListener((MapChangeListener<String, FolderStats>) _ -> refreshRows());
    viewModel.selectedFolderIdProperty().addListener((_, _, _) -> refreshRows());
    viewModel.settingProperty().addListener((_, _, setting) -> applySetting(setting));

    current = build();
    holder.getChildren().setAll(current);
  }

  public Region getNode() {
    return holder;
  }

  private void applySetting(Setting setting) {
    boolean target = setting != null && !setting.showSidePanel();
    if (!settingSeen) {
      settingSeen = true;
      if (target != collapsed) {
        collapsed = target;
        rebuild();
      }
      return;
    }
    if (target != collapsed) {
      animateTo(target);
    }
  }

  public void toggle() {
    animateTo(!collapsed);
    viewModel.setShowSidePanel(!collapsed);
  }

  private void rebuild() {
    current = build();
    holder.getChildren().setAll(current);
  }

  private void onFoldersChanged() {
    refreshRows();
    if (countLabel != null) {
      countLabel.setText(String.format("%02d", viewModel.folders().size()));
    }
  }

  private void refreshRows() {
    List<Node> rows = new ArrayList<>();
    for (FolderData folder : viewModel.folders()) {
      rows.add(folderRow(folder));
    }
    rowsBox.getChildren().setAll(rows);
  }

  private void animateTo(boolean target) {
    collapsed = target;
    double from = current.getWidth() > 0 ? current.getWidth() : (target ? EXPANDED : COLLAPSED);
    double to = target ? COLLAPSED : EXPANDED;
    VBox fresh = build();
    fresh.setMinWidth(from);
    fresh.setPrefWidth(from);
    fresh.setMaxWidth(from);
    Rectangle clip = new Rectangle(from, Math.max(current.getHeight(), 1));
    clip.heightProperty().bind(fresh.heightProperty());
    fresh.setClip(clip);
    holder.getChildren().setAll(fresh);
    current = fresh;
    Timeline tl =
        new Timeline(
            new KeyFrame(
                Duration.millis(300),
                new KeyValue(fresh.minWidthProperty(), to, Theme.EASE_IN_OUT),
                new KeyValue(fresh.prefWidthProperty(), to, Theme.EASE_IN_OUT),
                new KeyValue(fresh.maxWidthProperty(), to, Theme.EASE_IN_OUT),
                new KeyValue(clip.widthProperty(), to, Theme.EASE_IN_OUT)));
    tl.setOnFinished(e -> fresh.setClip(null));
    tl.play();
    FadeTransition fade = new FadeTransition(Duration.millis(260), fresh);
    fade.setFromValue(0.35);
    fade.setToValue(1);
    fade.setInterpolator(Theme.EASE_OUT);
    fade.play();
  }

  private VBox build() {
    VBox box = new VBox();
    box.getStyleClass().add("sidebar");
    box.setPrefWidth(collapsed ? COLLAPSED : EXPANDED);
    box.setMinWidth(collapsed ? COLLAPSED : EXPANDED);
    box.setPadding(new Insets(24, collapsed ? 12 : 18, 24, collapsed ? 12 : 18));

    countLabel = null;
    if (!collapsed) {
      HBox head = new HBox();
      head.setAlignment(Pos.CENTER_LEFT);
      head.setPadding(new Insets(0, 6, 16, 6));
      Label t = Ui.label("LIBRARIES", "side-head");
      Region sp = new Region();
      HBox.setHgrow(sp, Priority.ALWAYS);
      countLabel = Ui.label(String.format("%02d", viewModel.folders().size()), "side-count");
      head.getChildren().addAll(t, sp, countLabel);
      box.getChildren().add(head);
    }

    rowsBox = new VBox();
    VBox.setVgrow(rowsBox, Priority.ALWAYS);
    ScrollPane listScroll = new ScrollPane(rowsBox);
    listScroll.setFitToWidth(true);
    listScroll.getStyleClass().add("grid-scroll");
    VBox.setVgrow(listScroll, Priority.ALWAYS);
    refreshRows();
    box.getChildren().add(listScroll);

    // Import folder
    HBox importContent = new HBox(10);
    importContent.setAlignment(collapsed ? Pos.CENTER : Pos.CENTER_LEFT);
    if (!collapsed) {
      Label il = Ui.label("Import Folder", "import-label");
      HBox.setHgrow(il, Priority.ALWAYS);
      il.setMaxWidth(Double.MAX_VALUE);
      importContent.getChildren().add(il);
    }
    StackPane sq = new StackPane(Ui.ico("M7 2.5 L7 11.5 M2.5 7 L11.5 7", 1.6, Theme.BG));
    sq.getStyleClass().add("import-square");
    sq.setMinSize(32, 32);
    importContent.getChildren().add(sq);
    Button importBtn = Ui.button(importContent, "import-btn");
    importBtn.setPadding(new Insets(8));
    Ui.stretchGraphic(importBtn, importContent);
    importBtn.setAccessibleText("Import Folder");
    importBtn.setOnAction(e -> onImport.run());
    VBox.setMargin(importBtn, new Insets(16, 0, 0, 0));
    box.getChildren().add(importBtn);

    // Settings
    HBox settingsContent = new HBox(13);
    settingsContent.setAlignment(collapsed ? Pos.CENTER : Pos.CENTER_LEFT);
    SVGPath gear = Ui.ico(GEAR, 1.5);
    gear.setScaleX(0.72);
    gear.setScaleY(0.72);
    settingsContent.getChildren().add(gear);
    if (!collapsed) {
      Label sl = Ui.label("Settings", "settings-label");
      HBox.setHgrow(sl, Priority.ALWAYS);
      settingsContent.getChildren().add(sl);
    }
    Button settings = Ui.button(settingsContent, "settings-row");
    settings.setPadding(new Insets(11, 10, 11, 10));
    Ui.stretchGraphic(settings, settingsContent);
    settings.setAccessibleText("Settings");
    settings.setOnAction(e -> onSettings.run());
    VBox.setMargin(settings, new Insets(8, 0, 0, 0));
    box.getChildren().add(settings);

    return box;
  }

  private Node folderRow(FolderData folder) {
    boolean active = folder.position() == viewModel.selectedFolderIdProperty().get();

    HBox rowContent = new HBox(15);
    rowContent.setAlignment(collapsed ? Pos.CENTER : Pos.CENTER_LEFT);

    Label id = Ui.label(String.format("%02d", folder.position() + 1), "side-id");
    rowContent.getChildren().add(id);
    if (!collapsed) {
      Label name = Ui.label(folder.name(), "side-name");
      Label meta = Ui.label(meta(folder).toUpperCase(), "side-meta");
      VBox txt = new VBox(4, name, meta);
      HBox.setHgrow(txt, Priority.ALWAYS);
      rowContent.getChildren().add(txt);
    }

    Button row = Ui.button(rowContent, "side-item");
    row.pseudoClassStateChanged(ACTIVE, active);
    row.setPadding(new Insets(14, collapsed ? 0 : 8, 14, collapsed ? 0 : 8));
    Ui.stretchGraphic(row, rowContent);
    row.setAccessibleText(folder.name());
    row.setOnAction(
        e -> {
          onSelect.run();
          viewModel.selectFolder(folder.position());
        });

    StackPane wrap = new StackPane(row);
    if (active) {
      Region barAccent = new Region();
      barAccent.setMinWidth(2);
      barAccent.setMaxWidth(2);
      barAccent.setMaxHeight(Double.MAX_VALUE);
      barAccent.getStyleClass().add("side-accent-bar");
      barAccent.setMouseTransparent(true);
      StackPane.setAlignment(barAccent, Pos.CENTER_LEFT);
      wrap.getChildren().add(barAccent);
    }
    return wrap;
  }

  private String meta(FolderData folder) {
    FolderStats stats = viewModel.folderStats().get(folder.name());
    int items = stats == null ? 0 : stats.items();
    if (items == 0) {
      return "Empty · add files";
    }
    int types = Math.max(1, stats.types());
    return items + " Items · " + types + (types > 1 ? " Types" : " Type");
  }
}
