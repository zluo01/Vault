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
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import model.Media;
import model.Movie;
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
  void queriesAreAnsweredWhileABuildWritesItsResults() throws Exception {
    CountDownLatch writing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AppConfig config = new AppConfig(tempDir.resolve("data"));
    try (LibraryService service =
        new LibraryService(config, rescanThatStallsWhileWriting(writing, release))) {
      try {
        int position = service.insertFolder("Movies", tempDir.toString()).join();
        service.build("Movies", tempDir.toString(), position).join();
        service.build("Movies", tempDir.toString(), position);
        assertTrue(writing.await(5, TimeUnit.SECONDS), "the rescan never started writing");

        List<Media> shown =
            assertDoesNotThrow(
                () -> service.folderMedia(position, List.of()).get(2, TimeUnit.SECONDS),
                "a query waited for the rescan to finish writing");
        assertEquals(1, shown.size(), "the library must keep showing its last scan meanwhile");
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void aSettingChangedDuringABuildDoesNotHoldUpQueries() throws Exception {
    CountDownLatch writing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AppConfig config = new AppConfig(tempDir.resolve("data"));
    try (LibraryService service =
        new LibraryService(config, rescanThatStallsWhileWriting(writing, release))) {
      CompletableFuture<Void> change;
      try {
        int position = service.insertFolder("Movies", tempDir.toString()).join();
        service.build("Movies", tempDir.toString(), position).join();
        service.build("Movies", tempDir.toString(), position);
        assertTrue(writing.await(5, TimeUnit.SECONDS), "the rescan never started writing");

        change = service.setShowSidePanel(false);

        assertDoesNotThrow(
            () -> service.folderData(position).get(2, TimeUnit.SECONDS),
            "a query waited behind a setting change that was waiting for the rescan");
      } finally {
        release.countDown();
      }
      change.get(5, TimeUnit.SECONDS);
      assertFalse(service.settings().join().showSidePanel());
    }
  }

  /** The first scan finds one movie; the next one stalls inside the build's database write. */
  private LibraryService.MediaScanner rescanThatStallsWhileWriting(
      CountDownLatch writing, CountDownLatch release) {
    ParsedMedia movie =
        new ParsedMedia(
            Movie.builder().title("Heat").path(tempDir.resolve("Heat")).file("heat.mkv").build(),
            Map.of());
    AtomicInteger scans = new AtomicInteger();
    return (root, skip) -> {
      if (scans.getAndIncrement() == 0) {
        return List.of(movie);
      }
      // read by the cover thread and by the database write; the second reader is the write
      Set<Thread> readers = ConcurrentHashMap.newKeySet();
      return new AbstractList<>() {
        @Override
        public ParsedMedia get(int index) {
          if (readers.add(Thread.currentThread()) && readers.size() == 2) {
            writing.countDown();
          }
          park(release);
          return movie;
        }

        @Override
        public int size() {
          return 1;
        }
      };
    };
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

  // ---- ordering -----------------------------------------------------------

  @Test
  void queriesFinishInTheOrderTheyWereIssued() {
    try (LibraryService service = new LibraryService(new AppConfig(tempDir.resolve("data")))) {
      int count = 2_000;
      List<Integer> finished = Collections.synchronizedList(new ArrayList<>());
      List<CompletableFuture<Void>> all = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        int n = i;
        all.add(service.settings().thenRun(() -> finished.add(n)));
      }
      CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).join();

      assertEquals(count, finished.size());
      for (int i = 0; i < count; i++) {
        assertEquals(i, finished.get(i), "result " + i + " to arrive belonged to another query");
      }
    }
  }

  @Test
  void theLastOfSeveralQuickWritesWins() {
    try (LibraryService service = new LibraryService(new AppConfig(tempDir.resolve("data")))) {
      for (int round = 0; round < 200; round++) {
        CompletableFuture<Void> last = null;
        for (int i = 0; i <= 5; i++) {
          last = service.updateSkipFolders(List.of("round" + round + "-write" + i));
        }
        last.join();

        assertEquals(List.of("round" + round + "-write5"), service.settings().join().skipFolders());
      }
    }
  }

  @Test
  void deleteFolderRemovesTheFolderAndItsCovers() throws IOException {
    AppConfig config = new AppConfig(tempDir.resolve("data"));
    try (LibraryService service = new LibraryService(config)) {
      service.insertFolder("Movies", tempDir.toString()).join();
      Path cover = config.coversDir().resolve("Movies").resolve("John Wick").resolve("poster");
      Files.createDirectories(cover.getParent());
      Files.writeString(cover, "jpeg");

      service.deleteFolder("Movies").join();

      assertTrue(service.folderList().join().isEmpty());
      assertFalse(Files.exists(config.coversDir().resolve("Movies")), "covers were left behind");
    }
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
