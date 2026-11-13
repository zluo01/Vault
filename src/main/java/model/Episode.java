package model;

import java.nio.file.Path;

public record Episode(
    String title,
    String file,
    String season,
    String episode,
    String runtime,
    Path path,
    String preview)
    implements Media {

  @Override
  public String mainPoster() {
    return preview;
  }
}
