package model;

/**
 * Aggregate counts for one library folder, used for the sidebar meta line ("12 Items · 3 Types").
 *
 * @param items number of media rows in the folder
 * @param types number of distinct media types among them
 */
public record FolderStats(int items, int types) {}
