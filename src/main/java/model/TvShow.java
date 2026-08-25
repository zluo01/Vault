package model;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public record TvShow(String title, Path path, Map<String, String> posters) implements Media {
  public TvShow {
    posters = Map.copyOf(posters);
  }

  @Override
  public String mainPoster() {
    return posters.get("main");
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String title;
    private Path path;
    private Map<String, String> posters = new HashMap<>();

    private Builder() {}

    public Builder title(final String title) {
      this.title = title;
      return this;
    }

    public Builder path(final Path path) {
      this.path = path;
      return this;
    }

    public Builder posters(final Map<String, String> posters) {
      this.posters = new HashMap<>(posters);
      return this;
    }

    public Builder poster(final String name, final String value) {
      this.posters.put(name, value);
      return this;
    }

    public TvShow build() {
      return new TvShow(title, path, posters);
    }
  }
}
