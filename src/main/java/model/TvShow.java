package model;

import java.nio.file.Path;
import java.util.Map;

public record TvShow(String title, Path path, Map<String, String> posters) implements Media {
  public TvShow {
    posters = Map.copyOf(posters);
  }

  @Override
  public String mainPoster() {
    return posters.get("main");
  }
}
