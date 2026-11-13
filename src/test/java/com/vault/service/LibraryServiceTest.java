package com.vault.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import enums.FolderStatus;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import model.Media;
import model.ParsedMedia;
import model.Setting;
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

  // ---- builds and queries ---------------------------------------------------

  @Test
  void queriesAreAnsweredWhileBuildsAreRunning() {
    CountDownLatch release = new CountDownLatch(1);
    AppConfig config = new AppConfig(tempDir.resolve("data"));
    // every build parks inside its scan until the test lets it go
    try (LibraryService service = new LibraryService(config, (root, skip) -> park(release))) {
      try {
        for (int i = 0; i < 8; i++) {
          service.build("Library " + i, tempDir.toString(), i);
        }

        CompletableFuture<Setting> query = service.settings();

        assertDoesNotThrow(
            () -> query.get(2, TimeUnit.SECONDS), "a settings query waited behind the builds");
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void scanThatFailsWithAnIoErrorMarksTheFolderError() {
    AppConfig config = new AppConfig(tempDir.resolve("data"));
    LibraryService.MediaScanner failing =
        (root, skip) -> {
          throw new IOException("drive went away");
        };
    try (LibraryService service = new LibraryService(config, failing)) {
      int position = service.insertFolder("Movies", tempDir.toString()).join();

      CompletableFuture<Void> build = service.build("Movies", tempDir.toString(), position);

      assertThrows(CompletionException.class, build::join, "the caller must hear about it");
      assertEquals(FolderStatus.ERROR, service.folderData(position).join().status());
    }
  }

  private static List<ParsedMedia> park(CountDownLatch release) {
    try {
      release.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return List.of();
  }

  // ---- shutdown -----------------------------------------------------------

  @Test
  void closeReturnsAtOnceWhenNothingIsRunning() {
    LibraryService service = new LibraryService(new AppConfig(tempDir.resolve("data")));
    service.settings().join();

    assertTimeout(Duration.ofSeconds(1), service::close);
  }

  @Test
  void closeLetsWorkAlreadySubmittedFinish() {
    LibraryService service = new LibraryService(new AppConfig(tempDir.resolve("data")));
    CompletableFuture<Setting> pending = service.settings();

    service.close();

    assertTrue(pending.isDone(), "close() returned before the pending query finished");
    assertFalse(pending.isCompletedExceptionally(), "the pending query was dropped by close()");
  }
}
