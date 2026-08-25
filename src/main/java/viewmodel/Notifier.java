package viewmodel;

@FunctionalInterface
public interface Notifier {
  void show(String message);

  Notifier NOOP = message -> {};
}
