package ui.dialog;

import ui.MainView;

public final class ConfirmDialog {

  private ConfirmDialog() {}

  public static void show(
      MainView host, String title, String message, String confirmLabel, Runnable onConfirm) {
    new DialogCard(host, 400, title, message)
        .show(
            confirmLabel,
            () -> {
              onConfirm.run();
              return true;
            },
            null);
  }
}
