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

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String title;
    private Path path;
    private String poster;
    private String year;
    private String runtime;
    private String file;

    private Builder() {}

    public Builder title(final String title) {
      this.title = title;
      return this;
    }

    public Builder path(final Path path) {
      this.path = path;
      return this;
    }

    public Builder poster(final String poster) {
      this.poster = poster;
      return this;
    }

    public boolean isPosterSet() {
      return this.poster != null;
    }

    public Builder year(final String year) {
      this.year = year;
      return this;
    }

    public Builder runtime(final String runtime) {
      this.runtime = runtime;
      return this;
    }

    public Builder file(final String file) {
      this.file = file;
      return this;
    }

    public Movie build() {
      return new Movie(title, path, poster, year, runtime, file);
    }
  }
}
