package model;

import java.nio.file.Path;
import java.util.Objects;

public record Movie(
    String title, Path path, String poster, String year, String runtime, String file)
    implements Media {

  public Movie {
    Objects.requireNonNull(title);
    Objects.requireNonNull(path);
    Objects.requireNonNull(file);
    year = Objects.requireNonNullElse(year, "");
    runtime = Objects.requireNonNullElse(runtime, "");
  }

  @Override
  public String mainPoster() {
    return poster;
  }
}
