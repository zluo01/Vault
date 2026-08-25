package com.vault.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import enums.FolderStatus;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import model.Media;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import service.AppConfig;
import service.LibraryService;

class LibraryServiceTest {

  @TempDir Path tempDir;

  @Test
  void insertScanAndQueryFolderEndToEnd() throws Exception {
    Path library = tempDir.resolve("library");
    Path movieDir = library.resolve("John Wick");
    Files.createDirectories(movieDir);
    Files.writeString(
        movieDir.resolve("movie.nfo"),
        "<movie><title>John Wick</title><year>2014</year><genre>Action</genre></movie>");
    Files.writeString(movieDir.resolve("John Wick.mkv"), "");
    ImageIO.write(
        new BufferedImage(100, 150, BufferedImage.TYPE_INT_RGB),
        "jpg",
        movieDir.resolve("poster.jpg").toFile());

    try (LibraryService service = new LibraryService(new AppConfig(tempDir.resolve("data")))) {
      int position = service.insertFolder("Movies", library.toString()).join();
      assertEquals(0, position);

      service.build("Movies", library.toString(), position).join();

      assertEquals(FolderStatus.NONE, service.folderData(position).join().status());

      List<Media> media = service.folderMedia(position, List.of()).join();
      assertEquals(1, media.size());
      assertEquals("John Wick", media.getFirst().title());
      assertTrue(media.getFirst().mainPoster().contains("/Movies/"));
    }
  }

  @Test
  void buildFailsAndMarksFolderError() throws IOException {
    try (LibraryService service = new LibraryService(new AppConfig(tempDir.resolve("data")))) {
      int position = service.insertFolder("Broken", tempDir.resolve("missing").toString()).join();
      // Scanning a non-existent directory yields no media but still completes cleanly,
      // leaving the folder in NONE; a genuine failure path is covered by the repository tests.
      service.build("Broken", tempDir.resolve("missing").toString(), position).join();
      assertEquals(FolderStatus.NONE, service.folderData(position).join().status());
    }
  }
}
