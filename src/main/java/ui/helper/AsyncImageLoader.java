package ui.helper;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.image.Image;

public final class AsyncImageLoader {

  private static final long MAX_CACHE_BYTES = 128L * 1024 * 1024; // 128mb

  private static final ExecutorService EXECUTOR =
      Executors.newFixedThreadPool(
          3, Thread.ofPlatform().name("vault-image-loader-", 1).daemon().factory());

  private static final AsyncLoadingCache<Request, Image> CACHE =
      Caffeine.newBuilder()
          .maximumWeight(MAX_CACHE_BYTES)
          .weigher(AsyncImageLoader::imageBytes)
          .buildAsync(
              (request, _) -> CompletableFuture.supplyAsync(() -> loadBlocking(request), EXECUTOR));

  private static int imageBytes(final Request request, final Image image) {
    return (int) Math.min(Integer.MAX_VALUE, (long) (image.getWidth() * image.getHeight() * 4));
  }

  private AsyncImageLoader() {}

  public static void loadAsync(
      final String source,
      final double width,
      final double height,
      final Consumer<Image> onLoaded) {
    final Request request = Request.of(source, width, height);
    if (request == null) {
      deliver(onLoaded, null);
      return;
    }
    CACHE
        .get(request)
        .whenComplete((image, error) -> deliver(onLoaded, error == null ? image : null));
  }

  private static Image loadBlocking(final Request request) {
    final Optional<Path> local = localPath(request.source()).filter(Files::isRegularFile);
    if (local.isPresent()) {
      try (InputStream in = Files.newInputStream(local.get())) {
        Image image = new Image(in, request.width(), request.height(), true, true);
        return image.isError() ? null : image;
      } catch (IOException ignored) {
        return null;
      }
    }

    Image image = new Image(request.source(), request.width(), request.height(), true, true, false);
    return image.isError() ? null : image;
  }

  private static void deliver(final Consumer<Image> onLoaded, final Image image) {
    if (Platform.isFxApplicationThread()) {
      onLoaded.accept(image);
    } else {
      Platform.runLater(() -> onLoaded.accept(image));
    }
  }

  private static Optional<Path> localPath(final String source) {
    try {
      if (source.startsWith("file:")) {
        return Optional.of(Path.of(URI.create(source)));
      }
      final Path path = Path.of(source);
      return path.isAbsolute() ? Optional.of(path) : Optional.empty();
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  private record Request(String source, int width, int height) {
    static Request of(final String source, final double width, final double height) {
      if (source == null || source.isBlank()) {
        return null;
      }
      return new Request(
          source, Math.max(1, (int) Math.round(width)), Math.max(1, (int) Math.round(height)));
    }
  }
}
