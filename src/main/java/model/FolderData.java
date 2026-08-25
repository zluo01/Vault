package model;

import enums.FilterType;
import enums.FolderStatus;
import enums.SortType;

public record FolderData(
    String name,
    int position,
    String path,
    SortType sort,
    FilterType filterType,
    FolderStatus status) {

  public FolderData withStatus(FolderStatus status) {
    return new FolderData(name, position, path, sort, filterType, status);
  }
}
