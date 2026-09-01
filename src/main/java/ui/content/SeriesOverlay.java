package ui.content;

import enums.TagCategory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import model.Episode;
import model.FilterOption;
import model.FolderData;
import model.TvShow;
import ui.MainView;
import ui.Toast;
import ui.helper.AsyncImageLoader;
import ui.helper.CoverHelper;
import ui.helper.TimeUtils;
import ui.helper.Ui;
import viewmodel.LibraryViewModel;

public final class SeriesOverlay {

  private final LibraryViewModel viewModel;
  private final MainView host;
  private final Toast toast;

  private final ImageView keyArtCover = new ImageView();
  private String keyArtUrl;

  public SeriesOverlay(LibraryViewModel viewModel, MainView host, Toast toast) {
    this.viewModel = viewModel;
    this.host = host;
    this.toast = toast;
  }

  public Region build(TvShow show, int toneIndex) {
    VBox overlay = new VBox();
    overlay.getStyleClass().add("series");

    HBox backContent =
        new HBox(
            9, Ui.ico("M9 3 L4 8 L9 13", 1.4), Ui.label(libraryName().toUpperCase(), "back-label"));
    backContent.setAlignment(Pos.CENTER_LEFT);
    Button backRow = Ui.button(backContent, "back");
    backRow.setPadding(new Insets(26, 44, 4, 44));
    backRow.setMaxWidth(Region.USE_PREF_SIZE);
    backRow.setAccessibleText("Back to " + libraryName());
    backRow.setOnAction(e -> host.closeSeries());

    HBox body = new HBox(48);
    body.setAlignment(Pos.TOP_LEFT);
    body.setPadding(new Insets(24, 44, 48, 44));
    body.setMaxWidth(1280);

    Label genreVal = Ui.label("—", "meta-val");
    Label studioVal = Ui.label("—", "meta-val");
    Label catalogVal = Ui.label("—", "meta-val");
    VBox leftCol = buildLeftColumn(show, toneIndex, genreVal, studioVal, catalogVal);

    // ---- right column: title, meta line, tabs, episodes ----
    VBox rightCol = new VBox();
    HBox.setHgrow(rightCol, Priority.ALWAYS);
    rightCol.getChildren().add(Ui.label("SERIES", "kicker-sub"));
    Label st = new Label(show.title());
    st.getStyleClass().add("series-title");
    Label sdot = Ui.label(".", "accent-dot-series");
    HBox stRow = new HBox(0, st, sdot);
    VBox.setMargin(stRow, new Insets(12, 0, 0, 0));
    rightCol.getChildren().add(stRow);

    // genre/studio come from the tags table, loaded off-thread
    viewModel.fetchMediaTags(
        show,
        tags -> {
          String genres = joined(tags, TagCategory.GENRE.name());
          String studios = joined(tags, TagCategory.STUDIO.name());
          genreVal.setText(genres.isEmpty() ? "—" : genres);
          studioVal.setText(studios.isEmpty() ? "—" : studios);
        });

    VBox episodes = new VBox();

    // the season index strip: compact pills, all seasons visible, wraps only past ~30 seasons
    FlowPane tabs = new FlowPane(6, 6);
    tabs.setRowValignment(VPos.CENTER);
    VBox.setMargin(tabs, new Insets(30, 0, 6, 0));

    // only the episode list scrolls; key art, title and tabs stay fixed
    ScrollPane episodesScroll = new ScrollPane(episodes);
    episodesScroll.setFitToWidth(true);
    episodesScroll.getStyleClass().add("grid-scroll");
    VBox.setVgrow(episodesScroll, Priority.ALWAYS);
    VBox.setMargin(episodesScroll, new Insets(14, 0, 0, 0));

    rightCol.getChildren().addAll(tabs, episodesScroll);
    updateKeyArt(show, null);

    // seasons come from the episodes table, loaded off-thread
    viewModel.fetchEpisodes(
        show, seasons -> populateSeasons(show, leftCol, tabs, episodes, catalogVal, seasons));

    body.getChildren().addAll(leftCol, rightCol);

    // center the width-capped body so wide windows get balanced margins instead of a dead right
    // half
    StackPane bodyWrap = new StackPane(body);
    StackPane.setAlignment(body, Pos.TOP_CENTER);
    VBox.setVgrow(bodyWrap, Priority.ALWAYS);

    overlay.getChildren().addAll(backRow, bodyWrap);
    return overlay;
  }

  /** Build season tabs, the catalog line and the first season's rows once episodes arrive. */
  private void populateSeasons(
      TvShow show,
      VBox leftCol,
      FlowPane tabs,
      VBox episodes,
      Label catalogVal,
      Map<String, List<Episode>> seasons) {
    List<String> seasonKeys = List.copyOf(seasons.keySet());

    // Season strip: compact pills ("SP 01 02 …") so many seasons fit one row; hidden for
    // single-season shows.
    tabs.getChildren().clear();
    boolean showStrip = seasonKeys.size() > 1;
    tabs.setVisible(showStrip);
    tabs.setManaged(showStrip);
    if (showStrip) {
      tabs.getChildren().add(Ui.label("SEASON", "meta-key"));
      ToggleGroup group = new ToggleGroup();
      for (String key : seasonKeys) {
        ToggleButton tab = new ToggleButton("00".equals(key) ? "SP" : key);
        tab.getStyleClass().add("season-tab");
        tab.setMinWidth(38);
        tab.setToggleGroup(group);
        tab.setSelected(key.equals(seasonKeys.getFirst()));
        tab.setAccessibleText("00".equals(key) ? "Specials" : "Season " + key);
        tab.setOnAction(
            e -> {
              if (!tab.isSelected()) {
                // clicking the active tab must not leave the group with no selection
                tab.setSelected(true);
                return;
              }
              updateKeyArt(show, key);
              List<Episode> season = seasons.getOrDefault(key, List.of());
              updateCatalog(catalogVal, key, season.size());
              fillEpisodes(episodes, show, key, season);
            });
        tabs.getChildren().add(tab);
      }
    }

    if (seasonKeys.isEmpty()) {
      return;
    }
    String firstKey = seasonKeys.getFirst();
    List<Episode> firstSeason = seasons.get(firstKey);
    updateCatalog(catalogVal, firstKey, firstSeason.size());
    fillEpisodes(episodes, show, firstKey, firstSeason);
    updateKeyArt(show, firstKey);
  }

  /** Show the active season in the catalog meta row, e.g. "Season 3 · 13 Episodes". */
  private static void updateCatalog(Label catalogVal, String seasonKey, int episodeCount) {
    String season =
        "00".equals(seasonKey) ? "Specials" : "Season " + seasonKey.replaceFirst("^0+(?=.)", "");
    catalogVal.setText(
        season + " · " + episodeCount + (episodeCount == 1 ? " Episode" : " Episodes"));
  }

  /** Load the active season's poster into the key art, falling back to the main poster. */
  private void updateKeyArt(TvShow show, String seasonKey) {
    String url =
        seasonKey == null
            ? show.mainPoster()
            : show.posters().getOrDefault(seasonKey, show.mainPoster());
    keyArtUrl = url;
    // decode at the grid's 320x480 so both share one cache entry; the view scales to 288x432
    AsyncImageLoader.loadAsync(
        url,
        320,
        480,
        image -> {
          // apply only if this is still the most recently requested season art
          if (image != null && Objects.equals(url, keyArtUrl)) {
            CoverHelper.applyCover(keyArtCover, image, 288, 432);
          }
        });
  }

  /** Key art with cover-cropped poster and the genre/studio/catalog meta list. */
  private VBox buildLeftColumn(
      TvShow show, int toneIndex, Label genreVal, Label studioVal, Label catalogVal) {
    VBox leftCol = new VBox();
    leftCol.setMinWidth(288);
    leftCol.setMaxWidth(288);

    StackPane keyArt = new StackPane();
    keyArt.getStyleClass().add("keyart");
    keyArt.setMinSize(288, 432);
    keyArt.setMaxSize(288, 432);
    keyArt.getStyleClass().add("tone-" + Math.floorMod(toneIndex, 4));
    keyArtCover.setSmooth(true);
    keyArtCover.setPreserveRatio(false);
    Region kscrim = new Region();
    kscrim.getStyleClass().add("keyart-scrim");
    Region kframe = new Region();
    kframe.getStyleClass().add("keyart-frame");
    kframe.setMouseTransparent(true);
    keyArt.getChildren().addAll(keyArtCover, kscrim, kframe);
    leftCol.getChildren().add(keyArt);

    VBox metaList = new VBox(14);
    VBox.setMargin(metaList, new Insets(22, 0, 0, 0));
    metaList
        .getChildren()
        .addAll(
            metaRow("Genre", genreVal),
            metaRow("Studio", studioVal),
            metaRow("Catalog", catalogVal));
    leftCol.getChildren().add(metaList);
    return leftCol;
  }

  private void fillEpisodes(VBox box, TvShow show, String seasonKey, List<Episode> episodes) {
    box.getChildren().clear();
    for (int i = 0; i < episodes.size(); i++) {
      Episode episode = episodes.get(i);
      HBox rowContent = new HBox(20);
      rowContent.setAlignment(Pos.CENTER_LEFT);
      Button row = Ui.button(rowContent, "ep-row");
      row.setPadding(new Insets(16, 12, 16, 12));
      Ui.stretchGraphic(row, rowContent);
      row.setAccessibleText("E" + episode.episode() + " " + episode.title());
      row.setOnAction(e -> openEpisode(show, seasonKey, episode));

      Label epNum = Ui.label("E" + episode.episode(), "ep-num");
      epNum.setMinWidth(30);

      StackPane thumb = new StackPane();
      thumb.getStyleClass().add("ep-thumb");
      thumb.setMinSize(132, 74);
      thumb.setMaxSize(132, 74);
      thumb.getStyleClass().add("tone-" + Math.floorMod(i, 4));
      if (episode.preview() != null) {
        ImageView thumbView = new ImageView();
        thumb.getChildren().add(thumbView);
        AsyncImageLoader.loadAsync(
            episode.preview(),
            132,
            74,
            image -> {
              if (image != null) {
                CoverHelper.applyCover(thumbView, image, 132, 74);
              }
            });
      }
      SVGPath ring = Ui.ico("M12 2 A10 10 0 1 0 12.001 2", 1.2);
      ring.setStroke(Color.web("#ffffff", 0.55));
      SVGPath tri = new SVGPath();
      tri.setContent("M10 8.5 L15 12 L10 15.5 Z");
      tri.setFill(Color.web("#ffffff", 0.8));
      thumb.getChildren().addAll(ring, tri);

      VBox info = new VBox(6);
      HBox.setHgrow(info, Priority.ALWAYS);
      Label et = new Label(episode.title());
      et.getStyleClass().add("ep-title");
      info.getChildren().add(et);

      Label dur = Ui.label(TimeUtils.runtimeText(episode.runtime()), "ep-dur");
      rowContent.getChildren().addAll(epNum, thumb, info, dur);
      box.getChildren().add(row);
    }
  }

  private void openEpisode(TvShow show, String seasonKey, Episode episode) {
    toast.show(
        "Opening · " + show.title() + " · " + shortLabel(seasonKey) + " E" + episode.episode());
    viewModel.openEpisode(episode);
  }

  private Node metaRow(String key, Label value) {
    HBox r = new HBox();
    r.getStyleClass().add("meta-row");
    r.setAlignment(Pos.CENTER_LEFT);
    r.setPadding(new Insets(0, 0, 11, 0));
    Label k = Ui.label(key.toUpperCase(), "meta-key");
    k.setMinWidth(Region.USE_PREF_SIZE);
    value.setTextOverrun(OverrunStyle.ELLIPSIS);
    Tooltip tip = new Tooltip();
    tip.textProperty().bind(value.textProperty());
    value.setTooltip(tip);
    Region sp = new Region();
    sp.setMinWidth(18); // keeps a gap between key and value when the value is squeezed
    HBox.setHgrow(sp, Priority.ALWAYS);
    r.getChildren().addAll(k, sp, value);
    return r;
  }

  private static String joined(List<FilterOption> tags, String group) {
    return tags.stream()
        .filter(tag -> tag.group().equals(group))
        .map(FilterOption::label)
        .collect(Collectors.joining(" · "));
  }

  /** Season keys are zero-padded numbers; "00" is the specials bucket. */
  private static String shortLabel(String key) {
    return "00".equals(key) ? "SP" : "S" + key;
  }

  private String libraryName() {
    FolderData data = viewModel.currentFolderProperty().get();
    return data == null ? "LIBRARY" : data.name();
  }
}
