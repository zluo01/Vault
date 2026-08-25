package model;

import enums.TagCategory;
import java.util.List;
import java.util.Map;

public record ParsedMedia(Media media, Map<TagCategory, List<String>> tags) {

  public ParsedMedia {
    tags = Map.copyOf(tags);
  }

  public List<String> tags(TagCategory category) {
    return tags.getOrDefault(category, List.of());
  }
}
