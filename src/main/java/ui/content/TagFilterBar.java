package ui.content;

import enums.TagCategory;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.IntegerBinding;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Popup;
import javafx.stage.Screen;
import model.FilterOption;
import model.GroupedOption;
import ui.ThemeManager;
import ui.helper.Ui;
import viewmodel.LibraryViewModel;

final class TagFilterBar {

  private final LibraryViewModel viewModel;
  private final HBox facetsBox = new HBox(10);
  private final HBox chipBar = new HBox(8);

  private final ThemeManager themes;
  private ObservableList<FilterOption> observedTags;
  private final ListChangeListener<FilterOption> tagsListener = change -> rebuildChips();

  TagFilterBar(LibraryViewModel viewModel, ThemeManager themes) {
    this.viewModel = viewModel;
    this.themes = themes;

    facetsBox.setAlignment(Pos.CENTER_LEFT);
    chipBar.setAlignment(Pos.CENTER_LEFT);
    chipBar.setPadding(new Insets(18, 0, 0, 0));
    chipBar.setManaged(false);
    chipBar.setVisible(false);

    viewModel
        .tagGroups()
        .addListener((ListChangeListener<GroupedOption>) change -> rebuildFacets());
    viewModel.selectedFolderIdProperty().addListener((obs, old, value) -> observeTags());

    observeTags();
    rebuildFacets();
  }

  Node facetsNode() {
    return facetsBox;
  }

  Node chipBarNode() {
    return chipBar;
  }

  private void rebuildFacets() {
    facetsBox.getChildren().clear();
    facetsBox.getChildren().add(Ui.label("FILTER", "filter-label"));
    for (TagCategory category : TagCategory.values()) {
      facetsBox.getChildren().add(facetDropdown(category.display(), category.name()));
    }
  }

  private List<FilterOption> facetValues(String groupKey) {
    for (GroupedOption group : viewModel.tagGroups()) {
      if (group.label().equals(groupKey)) {
        return group.options();
      }
    }
    return List.of();
  }

  private Region facetDropdown(String facetName, String groupKey) {
    final int folderId = viewModel.selectedFolderIdProperty().get();
    final ObservableList<FilterOption> chosen = viewModel.selectedTags(folderId);
    final List<FilterOption> all = facetValues(groupKey);

    // ---- trigger button ----
    Label name = Ui.label(facetName, "facet-text");
    Label badge = Ui.label("", "facet-badge");
    SVGPath chev = Ui.ico(Ui.CHEVRON, 1.3);
    HBox content = new HBox(9, name, badge, chev);
    content.setAlignment(Pos.CENTER_LEFT);
    Button btn = Ui.button(content, "facet");
    btn.setPadding(new Insets(0, 13, 0, 13));
    btn.setMinHeight(36);
    btn.setMaxHeight(36);
    btn.setAccessibleText("Filter by " + facetName);
    IntegerBinding count =
        Bindings.createIntegerBinding(
            () -> (int) chosen.stream().filter(tag -> tag.group().equals(groupKey)).count(),
            chosen);
    badge.textProperty().bind(count.asString());
    badge.visibleProperty().bind(count.greaterThan(0));
    badge.managedProperty().bind(badge.visibleProperty());
    if (all.isEmpty()) {
      btn.setDisable(true);
      btn.setOpacity(0.4);
      return btn;
    }

    // ---- popup panel with sticky search + scrollable options ----
    Popup popup = new Popup();
    popup.setAutoHide(true);
    popup.setConsumeAutoHidingEvents(false);
    themes.watch(popup);

    VBox panel = new VBox();
    panel.getStyleClass().add("facet-pop");
    panel.setMinWidth(224);
    panel.setMaxWidth(224);

    TextField search = new TextField();
    search.setPromptText("Search " + facetName.toLowerCase(Locale.ROOT));
    search.getStyleClass().add("facet-search");
    HBox.setHgrow(search, Priority.ALWAYS);
    // inset search box with magnifier, sticky above the scrolling option list
    HBox searchBox =
        new HBox(9, Ui.ico("M6 2.5 A4 4 0 1 0 6.001 2.5 M9.2 9.7 L12.5 13", 1.3), search);
    searchBox.getStyleClass().add("facet-search-box");
    searchBox.setAlignment(Pos.CENTER_LEFT);
    VBox searchWrap = new VBox(searchBox);
    searchWrap.getStyleClass().add("facet-search-wrap");

    VBox rows = new VBox();
    // side insets match the search box, so hover bands align with its borders
    // while the scrollbar stays at the panel edge
    rows.setPadding(new Insets(0, 14, 0, 14));
    ScrollPane sp = new ScrollPane(rows);
    sp.setFitToWidth(true);
    sp.getStyleClass().add("facet-scroll");
    sp.setMaxHeight(258);
    VBox.setVgrow(sp, Priority.ALWAYS);

    Runnable rebuild =
        () -> {
          String q =
              search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
          rows.getChildren().clear();
          for (FilterOption option : all) {
            if (!q.isEmpty() && !option.label().toLowerCase(Locale.ROOT).contains(q)) {
              continue;
            }
            rows.getChildren().add(facetOption(folderId, option));
          }
          if (rows.getChildren().isEmpty()) {
            Label none = Ui.label("No matches", "facet-empty");
            none.setPadding(new Insets(14, 12, 14, 12));
            rows.getChildren().add(none);
          }
        };
    rebuild.run();
    search.textProperty().addListener((o, a, b) -> rebuild.run());

    panel.getChildren().addAll(searchWrap, sp);
    popup.getContent().add(panel);

    // Auto-hide fires on the press before the trigger's action runs, so a click meant to close
    // the popup would otherwise instantly re-open it — the toggle MenuButton implements itself.
    AtomicLong hiddenAt = new AtomicLong();
    btn.setOnAction(
        e -> {
          if (popup.isShowing() || System.currentTimeMillis() - hiddenAt.get() < 200) {
            popup.hide();
            return;
          }
          chev.setRotate(180);
          // rebuild before showing so the checks always reflect the current selection
          search.clear();
          rebuild.run();
          Bounds b = btn.localToScreen(btn.getBoundsInLocal());
          popup.show(btn, b.getMinX(), b.getMaxY() + 6);
          clampToScreen(popup, b);
          search.requestFocus();
        });
    popup.setOnHidden(
        e -> {
          chev.setRotate(0);
          hiddenAt.set(System.currentTimeMillis());
        });
    return btn;
  }

  /**
   * Keep a just-shown popup on screen: clamp X, and flip above the anchor if it runs off the bottom
   * — positioning a MenuButton would otherwise do for us.
   */
  private static void clampToScreen(Popup popup, Bounds anchor) {
    var screens =
        Screen.getScreensForRectangle(
            anchor.getMinX(), anchor.getMinY(), anchor.getWidth(), anchor.getHeight());
    if (screens.isEmpty()) {
      return;
    }
    Rectangle2D vis = screens.getFirst().getVisualBounds();
    double x = Math.clamp(popup.getX(), vis.getMinX(), vis.getMaxX() - popup.getWidth());
    double y = popup.getY();
    if (y + popup.getHeight() > vis.getMaxY()) {
      y = Math.max(vis.getMinY(), anchor.getMinY() - popup.getHeight() - 6);
    }
    popup.setX(x);
    popup.setY(y);
  }

  private Node facetOption(int folderId, FilterOption option) {
    CheckBox check = new CheckBox(option.label());
    check.getStyleClass().add("facet-check-box");
    check.setSelected(viewModel.hasTag(folderId, option));
    check.setMaxWidth(Double.MAX_VALUE);
    check.setPadding(new Insets(6, 12, 6, 12));
    check.setOnAction(e -> viewModel.modifyTag(folderId, option));
    return check;
  }

  // =====================================================================
  // Chips
  // =====================================================================
  private void observeTags() {
    if (observedTags != null) {
      observedTags.removeListener(tagsListener);
    }
    observedTags = viewModel.selectedTags(viewModel.selectedFolderIdProperty().get());
    observedTags.addListener(tagsListener);
    rebuildChips();
  }

  private void rebuildChips() {
    int folderId = viewModel.selectedFolderIdProperty().get();
    chipBar.getChildren().clear();
    boolean any = false;
    for (TagCategory category : TagCategory.values()) {
      for (FilterOption tag : viewModel.selectedTags(folderId)) {
        if (tag.group().equals(category.name())) {
          any = true;
          chipBar.getChildren().add(chip(folderId, category.display(), tag));
        }
      }
    }
    if (any) {
      Button clear = Ui.button("CLEAR ALL", "clear-all");
      clear.setOnAction(e -> viewModel.clearTags(folderId));
      chipBar.getChildren().add(clear);
    }
    chipBar.setManaged(any);
    chipBar.setVisible(any);
  }

  private Node chip(int folderId, String facetName, FilterOption tag) {
    HBox content =
        new HBox(
            8,
            Ui.label(facetName.toUpperCase(Locale.ROOT), "chip-facet"),
            Ui.label(tag.label(), "chip-val"),
            Ui.ico("M3 3 L9 9 M9 3 L3 9", 1.3));
    content.setAlignment(Pos.CENTER_LEFT);
    Button c = Ui.button(content, "chip");
    c.setPadding(new Insets(0, 8, 0, 11));
    c.setMinHeight(28);
    c.setAccessibleText("Remove " + facetName + " filter " + tag.label());
    c.setOnAction(e -> viewModel.modifyTag(folderId, tag));
    return c;
  }
}
