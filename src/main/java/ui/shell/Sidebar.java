package ui.shell;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
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
  private final Runnable onSelect;

  private final VBox box = new VBox();
  private final VBox rowsBox = new VBox();

  // One tree serves both states: its nodes follow this flag instead of being rebuilt.
  private final BooleanProperty collapsed = new SimpleBooleanProperty(false);
  private final ObservableValue<Pos> contentAlignment =
      collapsed.map(c -> c ? Pos.CENTER : Pos.CENTER_LEFT);
  private final ObservableValue<Insets> rowPadding =
      collapsed.map(c -> new Insets(14, c ? 0 : 8, 14, c ? 0 : 8));
  private Timeline animation;

  public Sidebar(
      LibraryViewModel viewModel, Runnable onImport, Runnable onSettings, Runnable onSelect) {
    this.viewModel = viewModel;
    this.onSelect = onSelect;

    box.getStyleClass().add("sidebar");
    box.paddingProperty().bind(collapsed.map(c -> new Insets(24, c ? 12 : 18, 24, c ? 12 : 18)));
    setWidth(EXPANDED);

    VBox.setVgrow(rowsBox, Priority.ALWAYS);
    ScrollPane listScroll = new ScrollPane(rowsBox);
    listScroll.setFitToWidth(true);
    listScroll.getStyleClass().add("grid-scroll");
    VBox.setVgrow(listScroll, Priority.ALWAYS);

    box.getChildren()
        .addAll(header(), listScroll, importButton(onImport), settingsButton(onSettings));

    viewModel.folders().addListener((ListChangeListener<FolderData>) _ -> refreshRows());
    refreshRows();

    // The persisted state only seeds the sidebar. From then on the sidebar owns it and just writes
    // it back, so a late answer from the database can never replay an old toggle.
    viewModel
        .settingProperty()
        .addListener(
            new ChangeListener<Setting>() {
              @Override
              public void changed(
                  ObservableValue<? extends Setting> source, Setting old, Setting setting) {
                if (setting != null) {
                  source.removeListener(this);
                  collapsed.set(!setting.showSidePanel());
                  setWidth(collapsed.get() ? COLLAPSED : EXPANDED);
                }
              }
            });
  }

  public Region getNode() {
    return box;
  }

  public void toggle() {
    animateTo(!collapsed.get());
    viewModel.setShowSidePanel(!collapsed.get());
  }

  /** Switch the content to the target state at once, then ease the width over to match. */
  private void animateTo(boolean target) {
    if (animation != null) {
      animation.stop();
    }
    collapsed.set(target);
    double to = target ? COLLAPSED : EXPANDED;
    // clipped while moving, so the already switched content never spills over the library grid
    Rectangle clip = new Rectangle(box.getMinWidth(), box.getHeight());
    clip.heightProperty().bind(box.heightProperty());
    box.setClip(clip);
    box.setOpacity(0.35);
    animation =
        new Timeline(
            new KeyFrame(Duration.millis(260), new KeyValue(box.opacityProperty(), 1, Ui.EASE_OUT)),
            new KeyFrame(
                Duration.millis(300),
                new KeyValue(box.minWidthProperty(), to, Ui.EASE_IN_OUT),
                new KeyValue(box.prefWidthProperty(), to, Ui.EASE_IN_OUT),
                new KeyValue(box.maxWidthProperty(), to, Ui.EASE_IN_OUT),
                new KeyValue(clip.widthProperty(), to, Ui.EASE_IN_OUT)));
    animation.setOnFinished(e -> box.setClip(null));
    animation.play();
  }

  private void setWidth(double width) {
    box.setMinWidth(width);
    box.setPrefWidth(width);
    box.setMaxWidth(width);
  }

  /** Show {@code node} only while the sidebar is expanded. */
  private <T extends Node> T expandedOnly(T node) {
    node.visibleProperty().bind(collapsed.not());
    node.managedProperty().bind(node.visibleProperty());
    return node;
  }

  /** A full-width button whose content is centred when collapsed and left-aligned otherwise. */
  private Button rowButton(HBox content, String styleClass, String name, Runnable action) {
    content.alignmentProperty().bind(contentAlignment);
    Button button = Ui.button(content, styleClass);
    Ui.stretchGraphic(button, content);
    button.setAccessibleText(name);
    button.setOnAction(e -> action.run());
    return button;
  }

  private Node header() {
    Label count = Ui.label("", "side-count");
    count.textProperty().bind(Bindings.size(viewModel.folders()).asString("%02d"));
    Region sp = new Region();
    HBox.setHgrow(sp, Priority.ALWAYS);
    HBox head = new HBox(Ui.label("LIBRARIES", "side-head"), sp, count);
    head.setAlignment(Pos.CENTER_LEFT);
    head.setPadding(new Insets(0, 6, 16, 6));
    return expandedOnly(head);
  }

  private Button importButton(Runnable onImport) {
    Label label = expandedOnly(Ui.label("Import Folder", "import-label"));
    HBox.setHgrow(label, Priority.ALWAYS);
    label.setMaxWidth(Double.MAX_VALUE);
    StackPane square = new StackPane(Ui.ico("M7 2.5 L7 11.5 M2.5 7 L11.5 7", 1.6, "ico-square"));
    square.getStyleClass().add("import-square");
    square.setMinSize(32, 32);

    Button button = rowButton(new HBox(10, label, square), "import-btn", "Import Folder", onImport);
    button.setPadding(new Insets(8));
    VBox.setMargin(button, new Insets(16, 0, 0, 0));
    return button;
  }

  private Button settingsButton(Runnable onSettings) {
    SVGPath gear = Ui.ico(GEAR, 1.5);
    gear.setScaleX(0.72);
    gear.setScaleY(0.72);
    Label label = expandedOnly(Ui.label("Settings", "settings-label"));
    HBox.setHgrow(label, Priority.ALWAYS);

    Button button = rowButton(new HBox(13, gear, label), "settings-row", "Settings", onSettings);
    button.setPadding(new Insets(11, 10, 11, 10));
    VBox.setMargin(button, new Insets(8, 0, 0, 0));
    return button;
  }

  private void refreshRows() {
    rowsBox.getChildren().setAll(viewModel.folders().stream().map(this::folderRow).toList());
  }

  /** One library row. Its meta line and highlight follow the view model without a rebuild. */
  private Node folderRow(FolderData folder) {
    Label id = Ui.label(String.format("%02d", folder.position() + 1), "side-id");
    Label meta = Ui.label("", "side-meta");
    meta.textProperty()
        .bind(
            Bindings.createStringBinding(
                () -> meta(folder).toUpperCase(), viewModel.folderStats()));
    VBox text = expandedOnly(new VBox(4, Ui.label(folder.name(), "side-name"), meta));
    HBox.setHgrow(text, Priority.ALWAYS);

    Button row =
        rowButton(
            new HBox(15, id, text),
            "side-item",
            folder.name(),
            () -> {
              onSelect.run();
              viewModel.selectFolder(folder.position());
            });
    row.paddingProperty().bind(rowPadding);

    Region accent = new Region();
    accent.setMinWidth(2);
    accent.setMaxWidth(2);
    accent.setMaxHeight(Double.MAX_VALUE);
    accent.getStyleClass().add("side-accent-bar");
    accent.setMouseTransparent(true);
    StackPane.setAlignment(accent, Pos.CENTER_LEFT);

    BooleanBinding active = viewModel.selectedFolderIdProperty().isEqualTo(folder.position());
    accent.visibleProperty().bind(active);
    row.pseudoClassStateChanged(ACTIVE, active.get());
    active.addListener((_, _, now) -> row.pseudoClassStateChanged(ACTIVE, now));

    return new StackPane(row, accent);
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
