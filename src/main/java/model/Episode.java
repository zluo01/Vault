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

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String title;
    private String file;
    private String season;
    private String episode;
    private String runtime;
    private Path path;
    private String preview;

    private Builder() {}

    public Builder title(final String title) {
      this.title = title;
      return this;
    }

    public Builder file(final String file) {
      this.file = file;
      return this;
    }

    public Builder season(final String season) {
      this.season = season;
      return this;
    }

    public Builder episode(final String episode) {
      this.episode = episode;
      return this;
    }

    public Builder runtime(final String runtime) {
      this.runtime = runtime;
      return this;
    }

    public Builder path(final Path path) {
      this.path = path;
      return this;
    }

    public Builder preview(final String preview) {
      this.preview = preview;
      return this;
    }

    public Episode build() {
      return new Episode(title, file, season, episode, runtime, path, preview);
    }
  }
}
