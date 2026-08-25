package com.vault.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import db.DatabaseService;
import enums.FilterType;
import enums.FolderStatus;
import enums.SortType;
import enums.TagCategory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import model.Episode;
import model.FilterOption;
import model.FolderData;
import model.FolderStats;
import model.GroupedOption;
import model.Media;
import model.Movie;
import model.ParsedMedia;
import model.Setting;
import model.TvShow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseServiceTest {

  private final Path covers = Path.of("covers");

  private DatabaseService repo;

  @BeforeEach
  void setUp() {
    repo = DatabaseService.create("jdbc:sqlite::memory:");
    repo.initialization();
  }

  @AfterEach
  void tearDown() {
    repo.close();
  }

  private static ParsedMedia movie(
      String title,
      String year,
      String file,
      List<String> genres,
      List<String> actors,
      List<String> studios) {
    Movie media =
        Movie.builder()
            .title(title)
            .path(Path.of("/movies/" + title))
            .poster("poster.jpg")
            .year(year)
            .runtime("120")
            .file(file)
            .build();
    return new ParsedMedia(
        media,
        Map.of(
            TagCategory.GENRE, genres,
            TagCategory.ACTOR, actors,
            TagCategory.STUDIO, studios));
  }

  private void seed() {
    repo.insertFolderData("Movie", Path.of("/movies"));
    repo.insertNewMedia(
        "Movie",
        List.of(
            movie(
                "John Wick",
                "2014",
                "John Wick.mkv",
                List.of("Action", "Thriller"),
                List.of("Keanu Reeves"),
                List.of("Summit Entertainment")),
            movie(
                "The Dark Knight",
                "2008",
                "The Dark Knight.mkv",
                List.of("Action", "Crime", "Drama"),
                List.of("Christian Bale"),
                List.of("Warner Bros")),
            movie(
                "Blade Runner",
                "1982",
                "Blade Runner.mkv",
                List.of("Thriller", "Sci-Fi"),
                List.of("Harrison Ford"),
                List.of()),
            movie(
                "Dune",
                "2021",
                "Dune.mkv",
                List.of("Action", "Sci-Fi", "Drama"),
                List.of(),
                List.of("Warner Bros")),
            movie(
                "Love Letter",
                "1995",
                "Love Letter.mp4",
                List.of("Drama", "Romance"),
                List.of(),
                List.of())));
  }

  private List<String> titles(List<Media> media) {
    return media.stream().map(Media::title).toList();
  }

  private static FilterOption tag(String group, String label) {
    return new FilterOption(group, label);
  }

  // --- tags ---

  @Test
  void mediaTagsReturnsGroupedTags() {
    seed();
    List<GroupedOption> result = repo.getFolderMediaTags(0);
    assertEquals(
        List.of(TagCategory.ACTOR.name(), TagCategory.GENRE.name(), TagCategory.STUDIO.name()),
        result.stream().map(GroupedOption::label).toList());
    List<String> genres =
        result.stream()
            .filter(g -> g.label().equals(TagCategory.GENRE.name()))
            .findFirst()
            .orElseThrow()
            .options()
            .stream()
            .map(FilterOption::label)
            .toList();
    assertEquals(List.of("Action", "Crime", "Drama", "Romance", "Sci-Fi", "Thriller"), genres);
  }

  @Test
  void mediaTagsEmptyFolderReturnsEmpty() {
    repo.insertFolderData("Empty", Path.of("/empty"));
    assertTrue(repo.getFolderMediaTags(0).isEmpty());
  }

  @Test
  void mediaTagsScopedToFolder() {
    seed();
    repo.insertFolderData("TV", Path.of("/tv"));
    repo.insertNewMedia("TV", List.of(animeShow()));
    List<GroupedOption> tv = repo.getFolderMediaTags(1);
    assertEquals(List.of(TagCategory.GENRE.name()), tv.stream().map(GroupedOption::label).toList());
    assertEquals(List.of(tag("GENRE", "Anime")), tv.getFirst().options());
    List<FilterOption> movieGenres =
        repo.getFolderMediaTags(0).stream()
            .filter(g -> g.label().equals(TagCategory.GENRE.name()))
            .findFirst()
            .orElseThrow()
            .options();
    assertFalse(movieGenres.contains(tag("GENRE", "Anime")));
  }

  @Test
  void mediaTagsOptionsCarryCategoryAsGroup() {
    seed();
    List<FilterOption> genres =
        repo.getFolderMediaTags(0).stream()
            .filter(g -> g.label().equals(TagCategory.GENRE.name()))
            .findFirst()
            .orElseThrow()
            .options();
    // The group field must round-trip into GET_FOLDER_CONTENT's tags JSON unchanged.
    assertTrue(genres.contains(tag("GENRE", "Action")));
  }

  @Test
  void getMediaTagsReturnsSingleItemTags() {
    seed();
    List<FilterOption> tags = repo.getMediaTags(Path.of("/movies/John Wick"));
    assertEquals(
        List.of(
            tag("ACTOR", "Keanu Reeves"),
            tag("GENRE", "Action"),
            tag("GENRE", "Thriller"),
            tag("STUDIO", "Summit Entertainment")),
        tags);
  }

  @Test
  void getMediaTagsUnknownPathReturnsEmpty() {
    seed();
    assertTrue(repo.getMediaTags(Path.of("/movies/Nope")).isEmpty());
  }

  @Test
  void duplicateTagsInsertOnce() {
    repo.insertFolderData("X", Path.of("/x"));
    ParsedMedia m =
        new ParsedMedia(
            Movie.builder().title("M").path(Path.of("/x/M")).file("m.mkv").build(),
            Map.of(TagCategory.GENRE, List.of("Action", "Action")));
    repo.insertNewMedia("X", List.of(m));
    assertEquals(List.of(tag("GENRE", "Action")), repo.getMediaTags(Path.of("/x/M")));
  }

  // --- folder stats ---

  @Test
  void getFolderStatsCountsItemsAndTypes() {
    seed();
    Map<String, FolderStats> stats = repo.getFolderStats();
    assertEquals(new FolderStats(5, 1), stats.get("Movie"));
  }

  @Test
  void getFolderStatsOmitsEmptyFolders() {
    repo.insertFolderData("Empty", Path.of("/empty"));
    assertTrue(repo.getFolderStats().isEmpty());
  }

  // --- tag filtering ---

  @Test
  void folderMediaNoTagsReturnsAll() {
    seed();
    assertEquals(5, repo.getFolderMedia(0, List.of(), covers).size());
  }

  @Test
  void folderMediaOrFilterSingleTag() {
    seed();
    List<String> titles = titles(repo.getFolderMedia(0, List.of(tag("GENRE", "Action")), covers));
    assertEquals(3, titles.size());
    assertTrue(titles.containsAll(List.of("John Wick", "The Dark Knight", "Dune")));
  }

  @Test
  void folderMediaOrFilterMultipleTagsSameGroup() {
    seed();
    List<String> titles =
        titles(
            repo.getFolderMedia(
                0, List.of(tag("GENRE", "Action"), tag("GENRE", "Thriller")), covers));
    assertEquals(4, titles.size());
    assertFalse(titles.contains("Love Letter"));
  }

  @Test
  void folderMediaAndFilterMultipleTagsSameGroup() {
    seed();
    repo.updateFolderFilterType(0);
    assertEquals(
        List.of("John Wick"),
        titles(
            repo.getFolderMedia(
                0, List.of(tag("GENRE", "Action"), tag("GENRE", "Thriller")), covers)));
  }

  @Test
  void folderMediaOrFilterAcrossGroupsNoOverlap() {
    seed();
    assertTrue(
        titles(
                repo.getFolderMedia(
                    0, List.of(tag("GENRE", "Romance"), tag("STUDIO", "Warner Bros")), covers))
            .isEmpty());
  }

  @Test
  void folderMediaOrFilterAcrossGroupsWithMatches() {
    seed();
    List<String> titles =
        titles(
            repo.getFolderMedia(
                0, List.of(tag("GENRE", "Drama"), tag("STUDIO", "Warner Bros")), covers));
    assertEquals(2, titles.size());
    assertTrue(titles.containsAll(List.of("The Dark Knight", "Dune")));
  }

  @Test
  void folderMediaNoMatchReturnsEmpty() {
    seed();
    assertTrue(repo.getFolderMedia(0, List.of(tag("GENRE", "Horror")), covers).isEmpty());
  }

  @Test
  void folderMediaAndFilterSingleTagMatchesLikeOr() {
    seed();
    repo.updateFolderFilterType(0);
    List<String> titles = titles(repo.getFolderMedia(0, List.of(tag("GENRE", "Action")), covers));
    assertEquals(3, titles.size());
    assertTrue(titles.containsAll(List.of("John Wick", "The Dark Knight", "Dune")));
  }

  @Test
  void folderMediaAndFilterAcrossGroups() {
    seed();
    repo.updateFolderFilterType(0);
    assertEquals(
        List.of("The Dark Knight"),
        titles(
            repo.getFolderMedia(
                0,
                List.of(
                    tag("GENRE", "Action"), tag("GENRE", "Crime"), tag("STUDIO", "Warner Bros")),
                covers)));
  }

  @Test
  void folderMediaAndFilterNoItemMatchesAllReturnsEmpty() {
    seed();
    repo.updateFolderFilterType(0);
    assertTrue(
        repo.getFolderMedia(0, List.of(tag("GENRE", "Action"), tag("GENRE", "Romance")), covers)
            .isEmpty());
  }

  @Test
  void folderMediaTagMatchRequiresSameCategory() {
    seed();
    // "Warner Bros" exists only as a STUDIO tag; selecting it as GENRE must not match.
    assertTrue(repo.getFolderMedia(0, List.of(tag("GENRE", "Warner Bros")), covers).isEmpty());
  }

  // --- folder scoping ---

  @Test
  void folderMediaScopedToRequestedFolder() {
    seed();
    repo.insertFolderData("TV", Path.of("/tv"));
    repo.insertNewMedia("TV", List.of(animeShow()));
    assertEquals(List.of("Show"), titles(repo.getFolderMedia(1, List.of(), covers)));
    assertEquals(5, repo.getFolderMedia(0, List.of(), covers).size());
  }

  @Test
  void folderMediaUnknownPositionReturnsEmpty() {
    seed();
    assertTrue(repo.getFolderMedia(9, List.of(), covers).isEmpty());
  }

  // --- media mapping ---

  @Test
  void folderMediaMapsTvShowWithSeasonPosters() {
    repo.insertFolderData("TV", Path.of("/tv"));
    repo.insertNewMedia("TV", List.of(animeShow()));
    Media media = repo.getFolderMedia(0, List.of(), covers).getFirst();
    TvShow loaded = assertInstanceOf(TvShow.class, media);
    assertTrue(loaded.mainPoster().contains("/TV/"), loaded.mainPoster());
    assertTrue(loaded.posters().containsKey("02"));
  }

  @Test
  void folderMediaFiltersTvShowTags() {
    repo.insertFolderData("TV", Path.of("/tv"));
    ParsedMedia film =
        new ParsedMedia(
            Movie.builder().title("Film").path(Path.of("/tv/Film")).file("f.mkv").build(),
            Map.of(TagCategory.GENRE, List.of("Action")));
    repo.insertNewMedia("TV", List.of(animeShow(), film));
    assertEquals(
        List.of("Show"), titles(repo.getFolderMedia(0, List.of(tag("GENRE", "Anime")), covers)));
  }

  @Test
  void folderMediaMovieWithoutPosterHasNoMainPoster() {
    repo.insertFolderData("Bare", Path.of("/bare"));
    ParsedMedia bare =
        new ParsedMedia(
            Movie.builder().title("Bare").path(Path.of("/bare/Bare")).file("b.mkv").build(),
            Map.of());
    repo.insertNewMedia("Bare", List.of(bare));
    assertNull(repo.getFolderMedia(0, List.of(), covers).getFirst().mainPoster());
  }

  private static ParsedMedia animeShow() {
    TvShow show =
        TvShow.builder()
            .title("Show")
            .path(Path.of("/tv/Show"))
            .poster("main", "poster.jpg")
            .poster("02", "season2.jpg")
            .build();
    return new ParsedMedia(show, Map.of(TagCategory.GENRE, List.of("Anime")));
  }

  // --- sorting ---

  @Test
  void folderMediaDefaultSortByPath() {
    seed();
    assertEquals(
        List.of("Blade Runner", "Dune", "John Wick", "Love Letter", "The Dark Knight"),
        titles(repo.getFolderMedia(0, List.of(), covers)));
  }

  @Test
  void folderMediaSortByTitleAscIgnoresPathOrder() {
    // Paths invert the title order so TITLE_ASC is distinguishable from the path default.
    repo.insertFolderData("X", Path.of("/x"));
    ParsedMedia zulu =
        new ParsedMedia(
            Movie.builder().title("Zulu").path(Path.of("/x/aa")).file("z.mkv").build(), Map.of());
    ParsedMedia alpha =
        new ParsedMedia(
            Movie.builder().title("Alpha").path(Path.of("/x/zz")).file("a.mkv").build(), Map.of());
    repo.insertNewMedia("X", List.of(zulu, alpha));
    assertEquals(List.of("Zulu", "Alpha"), titles(repo.getFolderMedia(0, List.of(), covers)));
    repo.updateSortType(0, SortType.TITLE_ASC);
    assertEquals(List.of("Alpha", "Zulu"), titles(repo.getFolderMedia(0, List.of(), covers)));
  }

  @Test
  void folderMediaSortByTitleDesc() {
    seed();
    repo.updateSortType(0, SortType.TITLE_DSC);
    assertEquals(
        List.of("The Dark Knight", "Love Letter", "John Wick", "Dune", "Blade Runner"),
        titles(repo.getFolderMedia(0, List.of(), covers)));
  }

  @Test
  void folderMediaSortByYearAsc() {
    seed();
    repo.updateSortType(0, SortType.YEAR_ASC);
    assertEquals(
        List.of("Blade Runner", "Love Letter", "The Dark Knight", "John Wick", "Dune"),
        titles(repo.getFolderMedia(0, List.of(), covers)));
  }

  @Test
  void folderMediaSortByYearDesc() {
    seed();
    repo.updateSortType(0, SortType.YEAR_DSC);
    assertEquals(
        List.of("Dune", "John Wick", "The Dark Knight", "Love Letter", "Blade Runner"),
        titles(repo.getFolderMedia(0, List.of(), covers)));
  }

  @Test
  void folderMediaPosterUrlsContainFolderName() {
    seed();
    Media first = repo.getFolderMedia(0, List.of(), covers).getFirst();
    assertTrue(first.mainPoster().contains("/Movie/"), first.mainPoster());
  }

  // --- episodes ---

  @Test
  void episodesGroupedByZeroPaddedSeason() {
    repo.insertFolderData("TV", Path.of("/tv"));
    TvShow show =
        TvShow.builder()
            .title("Show")
            .path(Path.of("/tv/Show"))
            .poster("main", "poster.jpg")
            .build();
    repo.insertNewMedia(
        "TV",
        List.of(
            new ParsedMedia(show, Map.of()),
            new ParsedMedia(episode("E1", "1", "1"), Map.of()),
            new ParsedMedia(episode("E2", "1", "2"), Map.of()),
            new ParsedMedia(episode("Special", "2", "1"), Map.of())));
    Map<String, List<Episode>> seasons = repo.getEpisodes("/tv/Show", "TV", Path.of("/tv"), covers);
    assertEquals(List.of("01", "02"), List.copyOf(seasons.keySet()));
    assertEquals(List.of("E1", "E2"), seasons.get("01").stream().map(Episode::title).toList());
  }

  private static Episode episode(String title, String season, String number) {
    return Episode.builder()
        .title(title)
        .file(title + ".mkv")
        .season(season)
        .episode(number)
        .runtime("1440")
        .path(Path.of("/tv/Show/Season " + season))
        .build();
  }

  // --- settings ---

  @Test
  void getSettingsReturnsDefaults() {
    Setting setting = repo.getSettings();
    assertTrue(setting.showSidePanel());
    assertTrue(setting.skipFolders().isEmpty());
  }

  @Test
  void updateAndGetSkipFolders() {
    final var expected = List.of("_Todo", "_Bonus");
    repo.updateSkipFolders(expected);
    assertEquals(expected, repo.getSettings().skipFolders());
  }

  @Test
  void updateHideSidePanelToggles() {
    repo.updateHideSidePanel(1);
    assertFalse(repo.getSettings().showSidePanel());
  }

  // --- folder operations ---

  @Test
  void insertFolderDataReturnsPosition() {
    assertEquals(0, repo.insertFolderData("Movie", Path.of("/movies")));
    assertEquals(1, repo.insertFolderData("TV", Path.of("/tv")));
  }

  @Test
  void insertAndGetFolderList() {
    repo.insertFolderData("Movie", Path.of("/movies"));
    repo.insertFolderData("TV", Path.of("/tv"));
    List<FolderData> folders = repo.getFolderList();
    assertEquals(List.of("Movie", "TV"), folders.stream().map(FolderData::name).toList());
    assertEquals(List.of(0, 1), folders.stream().map(FolderData::position).toList());
  }

  @Test
  void getFolderDataReturnsDefaults() {
    seed();
    FolderData data = repo.getFolderData(0);
    assertEquals("Movie", data.name());
    assertEquals(SortType.DEFAULT, data.sort());
    assertEquals(FilterType.OR, data.filterType());
    assertEquals(FolderStatus.NONE, data.status());
  }

  @Test
  void updateFolderPathChangesPath() {
    seed();
    repo.updateFolderPath(0, Path.of("/new/path"));
    assertEquals("/new/path", repo.getFolderData(0).path());
  }

  @Test
  void updateFolderFilterTypeToggles() {
    seed();
    repo.updateFolderFilterType(0);
    assertEquals(FilterType.AND, repo.getFolderData(0).filterType());
    repo.updateFolderFilterType(0);
    assertEquals(FilterType.OR, repo.getFolderData(0).filterType());
  }

  @Test
  void deleteFolderShiftsPositions() {
    repo.insertFolderData("A", Path.of("/a"));
    repo.insertFolderData("B", Path.of("/b"));
    repo.insertFolderData("C", Path.of("/c"));
    repo.deleteFolder("B");
    List<FolderData> folders = repo.getFolderList();
    assertEquals(List.of("A", "C"), folders.stream().map(FolderData::name).toList());
    assertEquals(List.of(0, 1), folders.stream().map(FolderData::position).toList());
  }

  @Test
  void deleteFolderCascadesMediaAndTags() {
    seed();
    repo.deleteFolder("Movie");
    assertTrue(repo.getFolderList().isEmpty());
    // Cascade requires foreign_keys=on; empty media and tag lists confirm it.
    assertTrue(repo.getFolderMedia(0, List.of(), covers).isEmpty());
    assertTrue(repo.getFolderMediaTags(0).isEmpty());
  }

  @Test
  void updateFolderStatusAndRecover() {
    seed();
    repo.updateFolderStatus(FolderStatus.LOADING, 0);
    assertEquals(FolderStatus.LOADING, repo.getFolderData(0).status());
    repo.recover();
    assertEquals(FolderStatus.ERROR, repo.getFolderData(0).status());
  }
}
