import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class SingleInstance {

  // Held for the whole process lifetime; the OS releases it on any exit, including crashes.
  @SuppressWarnings("unused")
  private static FileLock lock;

  private static volatile Runnable onActivate;

  private SingleInstance() {}

  /** Try to become the single instance; on success, start listening for activation requests. */
  static boolean acquire(final Path dataDir) {
    try {
      Files.createDirectories(dataDir);
      final FileChannel channel =
          FileChannel.open(
              dataDir.resolve("vault.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      lock = channel.tryLock();
      if (lock == null) {
        channel.close();
        return false;
      }
    } catch (IOException e) {
      // never refuse to start over a lock problem
      System.err.println("Single-instance lock unavailable: " + e.getMessage());
      return true;
    }
    try {
      listen(dataDir);
    } catch (IOException | UnsupportedOperationException e) {
      // no activation channel (e.g. no AF_UNIX support); still single-instance via the lock
      System.err.println("Single-instance activation unavailable: " + e.getMessage());
    }
    return true;
  }

  /** Ask the running instance to show its window. */
  static void activateExisting(final Path dataDir) {
    final var address = UnixDomainSocketAddress.of(dataDir.resolve("vault.sock"));
    try {
      // connecting is the whole message
      SocketChannel.open(address).close();
    } catch (IOException | UnsupportedOperationException e) {
      System.err.println("Could not reach the running instance: " + e.getMessage());
    }
  }

  /** Registered by the UI once the stage exists; invoked for every activation request. */
  static void onActivate(final Runnable action) {
    onActivate = action;
  }

  private static void listen(final Path dataDir) throws IOException {
    final Path socket = dataDir.resolve("vault.sock");
    Files.deleteIfExists(socket); // stale from a crash; the file lock proves it is ours
    final ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socket));
    Thread.ofPlatform()
        .name("vault-single-instance")
        .daemon()
        .start(
            () -> {
              while (true) {
                try (SocketChannel ignored = server.accept()) {
                  final Runnable action = onActivate;
                  if (action != null) {
                    action.run();
                  }
                } catch (IOException e) {
                  return;
                }
              }
            });
  }
}
