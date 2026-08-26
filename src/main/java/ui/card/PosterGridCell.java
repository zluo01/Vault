package ui.card;

import java.util.Objects;
import javafx.animation.TranslateTransition;
import javafx.beans.binding.DoubleExpression;
import javafx.beans.property.IntegerProperty;
import javafx.beans.value.ObservableDoubleValue;
import javafx.css.PseudoClass;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import model.Comic;
import model.Media;
import model.Movie;
import model.TvShow;
import org.controlsfx.control.GridCell;
import ui.helper.AsyncImageLoader;
import ui.helper.CoverHelper;
import ui.helper.TimeUtils;
import ui.helper.Ui;

public final class PosterGridCell extends GridCell<Media> {

  private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

  private final IntegerProperty selectedIndex;

  private final StackPane art = new StackPane();
  private final ImageView cover = new ImageView();
  private final Button folderBtn;
  private final Label title = new Label();
  private final Tooltip titleTip = new Tooltip();
  private final Label year = new Label();
  private final Label detail = new Label();
  private final VBox card;
  private final TranslateTransition lift;

  private Media current;
  private String currentPoster;
  private boolean hovered;
  private boolean lifted;

  public PosterGridCell(
      IntegerProperty selectedIndex, ObservableDoubleValue posterWidth, CardActions actions) {
    this.selectedIndex = selectedIndex;

    art.getStyleClass().add("poster");
    // Bind height to posterWidth instead of art.widthProperty() to prevent new cells from getting
    // stuck at 0-height.
    DoubleExpression width = DoubleExpression.doubleExpression(posterWidth);
    DoubleExpression height = width.multiply(1.5); // 2:3 poster ratio as the width flexes
    art.minWidthProperty().bind(width);
    art.prefWidthProperty().bind(width);
    art.maxWidthProperty().bind(width);
    art.minHeightProperty().bind(height);
    art.prefHeightProperty().bind(height);
    art.maxHeightProperty().bind(height);

    cover.setSmooth(true);
    cover.setPreserveRatio(false);
    cover.fitWidthProperty().bind(art.widthProperty());
    cover.fitHeightProperty().bind(art.heightProperty());
    art.widthProperty().addListener((o, a, b) -> crop());
    art.heightProperty().addListener((o, a, b) -> crop());

    Region scrim = new Region();
    scrim.getStyleClass().add("poster-scrim");

    title.getStyleClass().add("poster-title");
    title.setWrapText(false);
    title.setTextOverrun(OverrunStyle.ELLIPSIS);
    title.setTooltip(titleTip);
    title.maxWidthProperty().bind(width.subtract(32));
    StackPane.setAlignment(title, Pos.BOTTOM_LEFT);
    StackPane.setMargin(title, new Insets(0, 16, 16, 16));

    Region frame = new Region();
    frame.getStyleClass().add("poster-frame");
    frame.setMouseTransparent(true);

    // open container folder
    SVGPath folderIco =
        Ui.ico("M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z", 1.7);
    folderIco.setScaleX(0.62);
    folderIco.setScaleY(0.62);
    folderBtn = Ui.button(folderIco, "card-folder-btn");
    folderBtn.setPadding(Insets.EMPTY);
    folderBtn.setMinSize(30, 30);
    folderBtn.setMaxSize(30, 30);
    folderBtn.setFocusTraversable(false);
    folderBtn.setVisible(false);
    folderBtn.setTooltip(new Tooltip("Open in Folder"));
    folderBtn.setAccessibleText("Open in Folder");
    StackPane.setAlignment(folderBtn, Pos.TOP_RIGHT);
    StackPane.setMargin(folderBtn, new Insets(10, 10, 0, 0));
    folderBtn.setOnAction(
        e -> {
          Media media = getItem();
          if (media != null) {
            actions.openContainingFolder(media);
          }
        });
    // the click must not bubble into the cell's select/double-click-open handling
    folderBtn.addEventHandler(MouseEvent.MOUSE_CLICKED, Event::consume);

    art.getChildren().addAll(cover, scrim, title, frame, folderBtn);

    year.getStyleClass().add("card-year");
    detail.getStyleClass().add("card-detail");
    Region sp = new Region();
    HBox.setHgrow(sp, Priority.ALWAYS);
    HBox footer = new HBox(year, sp, detail);
    footer.setAlignment(Pos.CENTER_LEFT);

    card = new VBox(14, art, footer);

    lift = new TranslateTransition(Duration.millis(220), art);
    lift.setInterpolator(Ui.EASE_OUT);
    art.setOnMouseEntered(
        e -> {
          hovered = true;
          updateSelect();
        });
    art.setOnMouseExited(
        e -> {
          hovered = false;
          updateSelect();
        });

    setOnMouseClicked(
        e -> {
          Media media = getItem();
          if (media == null) {
            return;
          }
          actions.select(getIndex());
          if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
            actions.open(media, getIndex());
          }
        });

    selectedIndex.addListener((obs, old, value) -> updateSelectionStyle());
  }

  @Override
  protected void updateItem(Media media, boolean empty) {
    super.updateItem(media, empty);
    if (empty || media == null) {
      // Keep the poster image to prevent covers blinking on re-bind; loadPoster clears it when
      // the item really changes.
      current = null;
      setGraphic(null);
    } else {
      if (media != current) {
        current = media;
        title.setText(media.title() + ".");
        titleTip.setText(media.title());
        year.setText(yearText(media));
        detail.setText(detailText(media));
        loadPoster(media.mainPoster());
        setGraphic(card);
      }
      applyTone();
    }
    // reset the status
    lift.stop();
    art.setTranslateY(0);
    lifted = false;
    folderBtn.setVisible(false);
    updateSelectionStyle();
  }

  @Override
  public void updateIndex(int index) {
    super.updateIndex(index);
    if (!isEmpty() && getItem() != null) {
      applyTone();
    }
    updateSelectionStyle();
  }

  private void applyTone() {
    art.getStyleClass().removeIf(c -> c.startsWith("tone-"));
    art.getStyleClass().add("tone-" + Math.floorMod(Math.max(getIndex(), 0), 4));
  }

  private void loadPoster(String poster) {
    if (Objects.equals(poster, currentPoster) && cover.getImage() != null) {
      return;
    }
    currentPoster = poster;
    cover.setImage(null);
    AsyncImageLoader.loadAsync(
        poster,
        320,
        480,
        image -> {
          if (image != null && current != null && Objects.equals(poster, currentPoster)) {
            cover.setImage(image);
            crop();
          }
        });
  }

  private void crop() {
    CoverHelper.updateCoverViewport(cover, art.getWidth(), art.getHeight());
  }

  private static String yearText(Media media) {
    if (media instanceof Movie movie && !movie.year().isBlank()) {
      return movie.year();
    }
    return "—";
  }

  private static String detailText(Media media) {
    return switch (media) {
      case Movie movie -> TimeUtils.runtimeText(movie.runtime());
      case TvShow _ -> "SERIES";
      case Comic comic -> comic.pages() + " PAGES";
      default -> "—";
    };
  }

  private void updateSelectionStyle() {
    art.pseudoClassStateChanged(SELECTED, isSelectedCell());
    updateSelect();
  }

  private boolean isSelectedCell() {
    return !isEmpty() && getItem() != null && getIndex() == selectedIndex.get();
  }

  private void updateSelect() {
    boolean up = hovered || isSelectedCell();
    if (up == lifted) {
      return;
    }
    lifted = up;
    folderBtn.setVisible(up);
    lift.stop();
    lift.setToY(up ? -6 : 0);
    lift.play();
  }
}
