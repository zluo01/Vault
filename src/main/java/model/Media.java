package model;

import java.nio.file.Path;

public sealed interface Media permits Episode, Movie, TvShow {

  String title();

  Path path();

  String mainPoster();
}
