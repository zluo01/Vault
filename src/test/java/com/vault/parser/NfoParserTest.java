package com.vault.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import enums.TagCategory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import model.Episode;
import model.Movie;
import model.ParsedMedia;
import model.TvShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import parser.NfoParser;

class NfoParserTest {

  private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

  @Test
  void parsesMovieFields() throws IOException {
    ParsedMedia result =
        NfoParser.parse(
                FIXTURES.resolve("movies/sample1.nfo"),
                List.of(),
                List.of(Path.of("Blade Runner.mkv")))
            .orElseThrow();

    Movie movie = (Movie) result.media();
    assertEquals("Blade Runner", movie.title());
    assertEquals("1982", movie.year());
    assertEquals("118", movie.runtime());
    assertEquals("Blade Runner.mkv", movie.file());
    assertEquals(FIXTURES.resolve("movies"), movie.path());
    assertEquals(List.of("Science Fiction", "Drama", "Thriller"), result.tags(TagCategory.GENRE));
    assertTrue(result.tags(TagCategory.STUDIO).contains("Warner Bros. Pictures"));
    assertTrue(result.tags(TagCategory.ACTOR).contains("Harrison Ford"));
    assertTrue(result.tags(TagCategory.TAG).contains("cyberpunk"));
  }

  @Test
  void movieFallsBackToFirstPosterFile() throws IOException {
    ParsedMedia result =
        NfoParser.parse(
                FIXTURES.resolve("movies/sample1.nfo"),
                List.of(Path.of("poster.jpg")),
                List.of(Path.of("Blade Runner.mkv")))
            .orElseThrow();

    assertEquals("poster.jpg", ((Movie) result.media()).poster());
  }

  @Test
  void movieWithoutMediaFileThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> NfoParser.parse(FIXTURES.resolve("movies/sample2.nfo"), List.of(), List.of()));
  }

  @Test
  void parsesTvShowWithSeasonPosterMap() throws IOException {
    List<Path> posters =
        List.of(
            Path.of("poster.jpg"),
            Path.of("season01-poster.jpg"),
            Path.of("season-specials-poster.jpg"));

    ParsedMedia result =
        NfoParser.parse(FIXTURES.resolve("tvshows/sample1.nfo"), posters, List.of()).orElseThrow();

    TvShow show = (TvShow) result.media();
    assertEquals("Westworld", show.title());
    assertEquals(FIXTURES.resolve("tvshows"), show.path());
    assertEquals(
        Map.of(
            "main", "poster.jpg",
            "01", "season01-poster.jpg",
            "00", "season-specials-poster.jpg"),
        show.posters());
    assertEquals(
        List.of("Science Fiction", "Drama", "Adventure", "Western"),
        result.tags(TagCategory.GENRE));
    assertEquals(List.of("HBO"), result.tags(TagCategory.STUDIO));
    assertTrue(result.tags(TagCategory.ACTOR).contains("Anthony Hopkins"));
  }

  @Test
  void parsesEpisodeAndMatchesMediaAndPreviewByStem() throws IOException {
    ParsedMedia result =
        NfoParser.parse(
                FIXTURES.resolve("episodes/sample1.nfo"),
                List.of(Path.of("sample1-thumb.jpg")),
                List.of(Path.of("sample2.mkv"), Path.of("sample1.mkv")))
            .orElseThrow();

    Episode episode = (Episode) result.media();
    assertEquals("Pilot", episode.title());
    assertEquals("1", episode.season());
    assertEquals("1", episode.episode());
    assertEquals("43", episode.runtime());
    assertEquals("sample1.mkv", episode.file());
    assertEquals("sample1-thumb.jpg", episode.preview());
    assertEquals(FIXTURES.resolve("episodes"), episode.path());
  }

  @Test
  void episodeWithoutPosterLeavesPreviewUnset() throws IOException {
    ParsedMedia result =
        NfoParser.parse(
                FIXTURES.resolve("episodes/sample2.nfo"),
                List.of(),
                List.of(Path.of("sample2.mkv")))
            .orElseThrow();

    Episode episode = (Episode) result.media();
    assertEquals("Dulcinea", episode.title());
    assertNull(episode.preview());
  }

  @Test
  void episodeWithoutMatchingMediaFileThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NfoParser.parse(
                FIXTURES.resolve("episodes/sample3.nfo"),
                List.of(),
                List.of(Path.of("S02E05.mkv"))));
  }

  @Test
  void unknownRootTagThrowsIoException(@TempDir Path dir) throws IOException {
    Path nfo = dir.resolve("whatever.nfo");
    Files.writeString(nfo, "<artist><name>Someone</name></artist>");

    assertThrows(IOException.class, () -> NfoParser.parse(nfo, List.of(), List.of()));
  }
}
