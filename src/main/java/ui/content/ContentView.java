package ui.content;

import enums.FilterType;
import enums.FolderStatus;
import enums.SortType;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import model.FolderData;
import model.Media;
import model.TvShow;
import org.controlsfx.control.GridView;
import ui.MainView;
import ui.ThemeManager;
import ui.Toast;
import ui.card.CardActions;
import ui.card.PosterGridCell;
import ui.helper.Ui;
import viewmodel.LibraryViewModel;

public final class ContentView {

  private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

  private static final double MIN_CARD_WIDTH = 190;
  private static final double TARGET_CARD_WIDTH = 280;

  private static final double GAP_H = 26;
  private static final double GAP_V = 36;

  private static final double FOOTER_EXTRA = 30; // card VBox gap (14) + footer row (~16)
  private static final double SCROLLBAR_ALLOWANCE = 18;

  private final LibraryViewModel viewModel;
  private final MainView host;
  private final Toast toast;
  private final ThemeManager themes;

  private final VBox root = new VBox();
  private final Label headerKicker = Ui.label("01", "kicker");
  private final Label headerTitle = new Label("");
  private final TagFilterBar tagFilters;
  private final Label resultCount = Ui.label("0 ITEMS", "result-count");
  private final Label sortValue = Ui.label(SortType.DEFAULT.label(), "facet-text");
  private final Label filterTypeValue = Ui.label(FilterType.OR.name(), "facet-text");

  private final GridView<Media> grid;
  private final GridViewScroller scroller;
  private final StackPane gridHost = new StackPane();
  private final Node emptyBox;
  private final Node errorBox;
  private final IntegerProperty selectedIndex = new SimpleIntegerProperty(-1);
  private final DoubleProperty posterWidth = new SimpleDoubleProperty(MIN_CARD_WIDTH);
  private boolean resetScrollOnItems;

  private final Button refreshBtn;
  private final SVGPath refreshIcon;
  private final RotateTransition spin;
  private boolean scanning;

  public ContentView(LibraryViewModel viewModel, MainView host, Toast toast, ThemeManager themes) {
    this.viewModel = viewModel;
    this.host = host;
    this.toast = toast;
    this.themes = themes;
    this.tagFilters = new TagFilterBar(viewModel, themes);

    refreshIcon = Ui.ico("M21 12 A9 9 0 1 1 18.36 5.64 M21 3 L21 9 L15 9", 1.7, "ico-mid");
    refreshIcon.setScaleX(0.62);
    refreshIcon.setScaleY(0.62);
    refreshBtn = Ui.button(refreshIcon, "refresh-btn");
    refreshBtn.setPadding(Insets.EMPTY);
    refreshBtn.setMinSize(36, 36);
    refreshBtn.setMaxSize(36, 36);
    refreshBtn.setTooltip(new Tooltip("Rescan this library"));
    refreshBtn.setAccessibleText("Rescan this library");
    spin = new RotateTransition(Duration.millis(800), refreshIcon);
    spin.setByAngle(360);
    spin.setCycleCount(Animation.INDEFINITE);
    spin.setInterpolator(Interpolator.LINEAR);
    refreshBtn.setOnAction(e -> refresh());

    grid = new GridView<>(viewModel.visibleMedia());
    scroller = new GridViewScroller(grid);
    grid.getStyleClass().add("grid-view");
    grid.setHorizontalCellSpacing(GAP_H / 2);
    grid.setVerticalCellSpacing(GAP_V / 2);
    grid.setCellFactory(view -> new PosterGridCell(selectedIndex, posterWidth, cellActions()));
    grid.setFocusTraversable(true);
    grid.addEventFilter(KeyEvent.KEY_PRESSED, this::onGridKey);
    // Reflow only once the width settles: recomputing card metrics on every frame of the
    // sidebar's width animation re-crops all covers ~60 times and looks like tearing.
    PauseTransition resizeSettle = new PauseTransition(Duration.millis(120));
    resizeSettle.setOnFinished(e -> updateGridMetrics(grid.getWidth()));
    grid.widthProperty().addListener((obs, old, value) -> resizeSettle.playFromStart());
    updateGridMetrics(1180);

    emptyBox = emptyState();
    errorBox = errorState();

    buildMain();

    resultCount.textProperty().bind(viewModel.statusTextProperty());

    viewModel
        .visibleMedia()
        .addListener(
            (ListChangeListener<Media>)
                change -> {
                  selectedIndex.set(-1);
                  updatePlaceholders();
                  if (resetScrollOnItems) {
                    resetScrollOnItems = false;
                    scroller.scrollToTop();
                  }
                });
    viewModel.currentFolderProperty().addListener((obs, old, data) -> onFolderData(data));
    viewModel
        .selectedFolderIdProperty()
        .addListener(
            (obs, old, value) -> {
              selectedIndex.set(-1);
              resetScrollOnItems = true;
              scroller.scrollToTop();
            });

    onFolderData(viewModel.currentFolderProperty().get());
    updatePlaceholders();
  }

  public Region getNode() {
    return root;
  }

  public void focusGrid() {
    grid.requestFocus();
  }

  // =====================================================================
  // Static structure (header + filter bar + grid), built once
  // =====================================================================
  private void buildMain() {
    root.getStyleClass().add("main");

    VBox header = new VBox();
    header.setPadding(new Insets(34, 44, 0, 44));

    HBox kickerRow = new HBox(11);
    kickerRow.setAlignment(Pos.CENTER_LEFT);
    Region tick = new Region();
    tick.setMinSize(28, 1);
    tick.setPrefSize(28, 1);
    tick.setMaxSize(28, 1);
    tick.getStyleClass().add("kicker-tick");
    kickerRow.getChildren().addAll(headerKicker, tick, Ui.label("COLLECTION", "kicker-sub"));
    VBox.setMargin(kickerRow, new Insets(0, 0, 16, 0));

    headerTitle.getStyleClass().add("h1");
    Label hdot = Ui.label(".", "accent-dot-h1");
    HBox titleRow = new HBox(0, headerTitle, hdot);

    HBox titleAndSearch = new HBox(40);
    titleAndSearch.setAlignment(Pos.BOTTOM_LEFT);
    titleAndSearch.setFillHeight(false);
    VBox titleBlock = new VBox(kickerRow, titleRow);
    Region g1 = new Region();
    HBox.setHgrow(g1, Priority.ALWAYS);
    titleAndSearch.getChildren().addAll(titleBlock, g1, buildSearch());

    header.getChildren().add(titleAndSearch);
    header.getChildren().add(buildFilterBar());

    header.getChildren().add(tagFilters.chipBarNode());

    Region divider = new Region();
    divider.setMinHeight(1);
    divider.getStyleClass().add("header-divider");
    VBox.setMargin(divider, new Insets(24, 0, 0, 0));
    header.getChildren().add(divider);

    // Cell spacing already contributes 13/18 at the edges; the margins top it up to the
    // Vault grid padding of (28, 44, 40, 44).
    gridHost.getChildren().addAll(grid, emptyBox, errorBox);
    VBox.setVgrow(gridHost, Priority.ALWAYS);
    VBox.setMargin(gridHost, new Insets(15, 31, 22, 31));

    root.getChildren().addAll(header, gridHost);
  }

  private Node buildSearch() {
    HBox box = new HBox(10);
    box.getStyleClass().add("search");
    box.setAlignment(Pos.CENTER_LEFT);
    box.setMinWidth(260);
    box.setMaxWidth(260);
    box.setPadding(new Insets(0, 0, 9, 0));
    box.getChildren().add(Ui.ico("M7 3 A4 4 0 1 0 7.001 3 M10.2 10.2 L14 14", 1.2));
    TextField tf = new TextField();
    tf.setPromptText("Search this library");
    tf.getStyleClass().add("search-field");
    HBox.setHgrow(tf, Priority.ALWAYS);
    tf.textProperty().bindBidirectional(viewModel.searchKeyProperty());
    box.getChildren().add(tf);
    return box;
  }

  private Node buildFilterBar() {
    HBox bar = new HBox(24);
    bar.setAlignment(Pos.CENTER_LEFT);
    VBox.setMargin(bar, new Insets(30, 0, 0, 0));

    Region sp = new Region();
    HBox.setHgrow(sp, Priority.ALWAYS);

    bar.getChildren()
        .addAll(
            tagFilters.facetsNode(),
            filterTypeToggle(),
            sp,
            resultCount,
            sortDropdown(),
            refreshBtn);
    return bar;
  }

  /** OR/AND toggle: whether media must carry any or all of the selected tags. */
  private Region filterTypeToggle() {
    Label tag = Ui.label("Match", "sort-tag");
    HBox content = new HBox(9, tag, filterTypeValue);
    content.setAlignment(Pos.CENTER_LEFT);
    Button btn = Ui.button(content, "facet");
    btn.setPadding(new Insets(0, 13, 0, 13));
    btn.setMinHeight(36);
    btn.setMaxHeight(36);
    btn.setTooltip(new Tooltip("OR shows media matching any selected tag; AND requires all"));
    btn.setAccessibleText("Tag match mode");
    btn.setOnAction(e -> viewModel.switchFilterType());
    return btn;
  }

  // =====================================================================
  // Folder state (title, sort label, scanning/error)
  // =====================================================================
  private void onFolderData(FolderData data) {
    if (data != null) {
      headerTitle.setText(data.name());
      headerKicker.setText(String.format("%02d", data.position() + 1));
      sortValue.setText(data.sort().label());
      filterTypeValue.setText(data.filterType().name());
    } else {
      headerTitle.setText("");
      headerKicker.setText("00");
      sortValue.setText(SortType.DEFAULT.label());
      filterTypeValue.setText(FilterType.OR.name());
    }
    setScanning(data != null && data.status() == FolderStatus.LOADING);
    updatePlaceholders();
  }

  private void refresh() {
    FolderData data = viewModel.currentFolderProperty().get();
    if (scanning || data == null) {
      return;
    }
    viewModel.refreshLibrary(data.name(), data.path(), data.position());
  }

  /** Vault scanning treatment: spin the refresh icon, dim the grid, show SCANNING. */
  private void setScanning(boolean loading) {
    if (scanning == loading) {
      return;
    }
    scanning = loading;
    refreshBtn.pseudoClassStateChanged(ACTIVE, loading);
    if (loading) {
      refreshIcon.getStyleClass().setAll("ico-active");
      spin.play();
      gridHost.setOpacity(0.45);
      gridHost.setDisable(true);
      viewModel.setStatus("SCANNING…");
      resultCount.getStyleClass().add("result-count-active");
    } else {
      spin.stop();
      refreshIcon.setRotate(0);
      refreshIcon.getStyleClass().setAll("ico-mid");
      gridHost.setDisable(false);
      resultCount.getStyleClass().remove("result-count-active");
      FadeTransition back = new FadeTransition(Duration.millis(260), gridHost);
      back.setFromValue(0.45);
      back.setToValue(1.0);
      back.play();
      viewModel.resetStatusToCount();
    }
  }

  /** Show the grid, the Vault empty state or the build-failed state. */
  private void updatePlaceholders() {
    FolderData data = viewModel.currentFolderProperty().get();
    boolean error = data != null && data.status() == FolderStatus.ERROR;
    boolean empty = !error && viewModel.visibleMedia().isEmpty();
    errorBox.setVisible(error);
    errorBox.setManaged(error);
    emptyBox.setVisible(empty);
    emptyBox.setManaged(empty);
    grid.setVisible(!error);
  }

  // =====================================================================
  // Sort dropdown
  // =====================================================================
  private Region sortDropdown() {
    Label tag = Ui.label("Sort", "sort-tag");
    SVGPath chev = Ui.ico(Ui.CHEVRON, 1.3);
    HBox content = new HBox(9, tag, sortValue, chev);
    content.setAlignment(Pos.CENTER_LEFT);
    Button btn = Ui.button(content, "facet");
    btn.setPadding(new Insets(0, 13, 0, 13));
    btn.setMinHeight(36);
    btn.setMaxHeight(36);
    btn.setAccessibleText("Sort order");

    ContextMenu menu = new ContextMenu();
    menu.getStyleClass().add("sort-pop");
    menu.setConsumeAutoHidingEvents(false);
    themes.watch(menu);

    AtomicLong hiddenAt = new AtomicLong();
    btn.setOnAction(
        e -> {
          if (menu.isShowing() || System.currentTimeMillis() - hiddenAt.get() < 200) {
            menu.hide();
            return;
          }
          FolderData data = viewModel.currentFolderProperty().get();
          SortType current = data == null ? SortType.DEFAULT : data.sort();
          menu.getItems().clear();
          for (SortType sort : SortType.values()) {
            menu.getItems().add(sortItem(sort, sort == current));
          }
          chev.setRotate(180);
          menu.show(btn, Side.BOTTOM, btn.getWidth() - 168, 6);
        });
    menu.setOnHidden(
        e -> {
          chev.setRotate(0);
          hiddenAt.set(System.currentTimeMillis());
        });
    return btn;
  }

  private MenuItem sortItem(SortType sort, boolean on) {
    Label dot = new Label();
    dot.setMinSize(5, 5);
    dot.setMaxSize(5, 5);
    if (on) {
      dot.getStyleClass().add("sort-dot-on");
    }
    Label lbl = Ui.label(sort.label(), on ? "facet-opt-on" : "facet-opt");
    HBox.setHgrow(lbl, Priority.ALWAYS);
    lbl.setMaxWidth(Double.MAX_VALUE);
    HBox row = new HBox(11, lbl, dot);
    row.setAlignment(Pos.CENTER_LEFT);
    row.setPadding(new Insets(9, 10, 9, 10));
    row.setPrefWidth(156);
    CustomMenuItem item = new CustomMenuItem(row, true);
    item.setOnAction(e -> viewModel.updateSort(sort));
    return item;
  }

  // =====================================================================
  // Cards: open behavior and footer text
  // =====================================================================
  private CardActions cellActions() {
    return new CardActions() {
      @Override
      public void select(int index) {
        selectIndex(index);
        grid.requestFocus();
      }

      @Override
      public void open(Media media, int index) {
        openItem(media, index);
      }

      @Override
      public void openContainingFolder(Media media) {
        viewModel.openContainingFolder(media);
      }
    };
  }

  private void openItem(Media media, int toneIndex) {
    if (media instanceof TvShow show) {
      host.showSeries(show, toneIndex);
    } else {
      toast.show("Opening · " + media.title());
      viewModel.openMedia(media);
    }
  }

  private Node emptyState() {
    VBox e = new VBox(9);
    e.setAlignment(Pos.CENTER);
    e.setPadding(new Insets(100, 0, 0, 0));
    e.setMaxHeight(Region.USE_PREF_SIZE);
    e.setMouseTransparent(true);
    SVGPath mag = Ui.ico("M11 4 A7 7 0 1 0 11.001 4 M16 16 L21 21", 1.2, "ico-faint");
    Label t = new Label("Nothing here yet.");
    t.getStyleClass().add("empty-title");
    Label s = Ui.label("IMPORT A FOLDER OR ADJUST YOUR FILTERS", "empty-sub");
    e.getChildren().addAll(mag, t, s);
    StackPane.setAlignment(e, Pos.TOP_CENTER);
    return e;
  }

  private Node errorState() {
    VBox e = new VBox(9);
    e.setAlignment(Pos.CENTER);
    e.setPadding(new Insets(100, 0, 0, 0));
    e.setMaxHeight(Region.USE_PREF_SIZE);
    e.setMouseTransparent(true);
    SVGPath warn = Ui.ico("M12 3 L22 21 L2 21 Z M12 10 L12 15 M12 18 L12 18.01", 1.2, "ico-faint");
    Label t = new Label("Directory build failed.");
    t.getStyleClass().add("empty-title");
    Label s = Ui.label("REFRESH TO RETRY", "empty-sub");
    e.getChildren().addAll(warn, t, s);
    StackPane.setAlignment(e, Pos.TOP_CENTER);
    return e;
  }

  // =====================================================================
  // Responsive layout: pick the column count whose card width lands closest
  // to the ~280px target (never below the minimum), then stretch to fill.
  // =====================================================================
  private void updateGridMetrics(double width) {
    if (width <= 0) {
      return;
    }
    double rowWidth = Math.max(1, width - SCROLLBAR_ALLOWANCE);
    double targetSpan = TARGET_CARD_WIDTH + GAP_H;
    double minSpan = MIN_CARD_WIDTH + GAP_H;
    int maxColumns = Math.max(1, (int) Math.floor(rowWidth / minSpan));
    int columns = Math.max(1, (int) Math.floor(rowWidth / targetSpan));
    if (columns < maxColumns && isNextColumnCloser(rowWidth, columns, targetSpan, minSpan)) {
      columns++;
    }
    double cardWidth = Math.max(1, Math.floor(rowWidth / columns) - GAP_H);
    double cellHeight = Math.round(cardWidth * 1.5) + FOOTER_EXTRA;

    if (Math.round(grid.getCellWidth()) != Math.round(cardWidth)) {
      grid.setCellWidth(cardWidth);
    }
    if (Math.round(grid.getCellHeight()) != Math.round(cellHeight)) {
      grid.setCellHeight(cellHeight);
    }
    if (Math.round(posterWidth.get()) != Math.round(cardWidth)) {
      posterWidth.set(cardWidth);
    }
  }

  private static boolean isNextColumnCloser(
      double rowWidth, int columns, double targetSpan, double minSpan) {
    double currentSpan = rowWidth / columns;
    double nextSpan = rowWidth / (columns + 1);
    return nextSpan >= minSpan
        && Math.abs(nextSpan - targetSpan) < Math.abs(currentSpan - targetSpan);
  }

  private int columnCount() {
    double rowWidth = Math.max(0, grid.getWidth() - SCROLLBAR_ALLOWANCE);
    double cellWidth = grid.getCellWidth() + grid.getHorizontalCellSpacing() * 2;
    return Math.max(1, (int) Math.floor(rowWidth / cellWidth));
  }

  // =====================================================================
  // Keyboard navigation (virtualized: scroll rows into view via the flow)
  // =====================================================================
  private void onGridKey(KeyEvent e) {
    List<Media> items = viewModel.visibleMedia();
    if (items.isEmpty()) {
      return;
    }
    int cols = columnCount();
    int current = Math.max(selectedIndex.get(), 0);
    if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
      if (selectedIndex.get() >= 0) {
        openItem(items.get(selectedIndex.get()), selectedIndex.get());
      }
      selectIndex(current);
      e.consume();
      return;
    }
    int target =
        switch (e.getCode()) {
          case RIGHT -> Math.min(items.size() - 1, current + 1);
          case LEFT -> Math.max(0, current - 1);
          case DOWN -> Math.min(items.size() - 1, current + cols);
          case UP -> Math.max(0, current - cols);
          case HOME -> 0;
          case END -> items.size() - 1;
          default -> -1;
        };
    if (target < 0) {
      return;
    }
    // the first navigation key focuses the first card
    selectIndex(selectedIndex.get() < 0 ? 0 : target);
    e.consume();
  }

  private void selectIndex(int index) {
    int size = viewModel.visibleMedia().size();
    if (index < 0 || index >= size) {
      return;
    }
    int previous = selectedIndex.get();
    selectedIndex.set(index);
    scroller.scrollRowIntoView(index / columnCount(), Integer.compare(index, previous));
  }
}
