package model;

import java.util.List;

public record GroupedOption(String label, List<FilterOption> options) {
  public GroupedOption {
    options = List.copyOf(options);
  }
}
