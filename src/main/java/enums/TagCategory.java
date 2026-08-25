package enums;

/**
 * The tag groups a media item can carry. {@link #name()} is the identifier stored in the tags table
 * and used in filter queries; {@link #display()} is the facet label shown in the UI. Enum order is
 * the facet display order.
 */
public enum TagCategory {
  GENRE("Genre"),
  STUDIO("Studio"),
  ACTOR("Actor"),
  TAG("Tag");

  private final String display;

  TagCategory(String display) {
    this.display = display;
  }

  public String display() {
    return display;
  }
}
