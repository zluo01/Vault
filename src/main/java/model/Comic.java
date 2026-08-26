package model;

import java.nio.file.Path;
import java.util.Objects;

/**
 * @param title comic file name
 * @param path comic absolute path
 * @param file comic file name
 * @param poster on parsing, this is the relative name to the folder. From DB, this will be the json
 *     with the path to the poster
 * @param pages number of pages in this comic file
 */
public record Comic(String title, Path path, String file, String poster, int pages)
    implements Media {

  public Comic {
    Objects.requireNonNull(title);
    Objects.requireNonNull(path);
    Objects.requireNonNull(file);
  }

  @Override
  public String mainPoster() {
    return poster;
  }
}
