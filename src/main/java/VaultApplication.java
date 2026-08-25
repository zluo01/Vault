import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import service.AppConfig;
import service.LibraryService;
import ui.MainView;
import ui.Theme;
import ui.Toast;
import ui.shell.WindowResizer;
import viewmodel.LibraryViewModel;

public class VaultApplication extends Application {

  private LibraryService service;

  @Override
  public void start(Stage stage) {
    service = new LibraryService(AppConfig.resolveDefault());

    Toast toast = new Toast();
    LibraryViewModel viewModel = new LibraryViewModel(service, toast, getHostServices());
    MainView mainView = new MainView(viewModel, stage, toast);
    toast.attachTo(mainView.getRoot());

    Scene scene = new Scene(mainView.getRoot(), 1180, 760);
    scene.setFill(Theme.BACKGROUND);
    scene.getStylesheets().add(getClass().getResource(Theme.STYLESHEET).toExternalForm());

    stage.initStyle(StageStyle.UNDECORATED);
    stage.setMinWidth(900);
    stage.setMinHeight(600);
    stage.setScene(scene);
    stage.setTitle("Vault");
    stage.setMaximized(true);
    WindowResizer.install(stage, scene);
    stage.show();

    // a second launch asks us to come to front instead of starting again
    SingleInstance.onActivate(
        () ->
            Platform.runLater(
                () -> {
                  stage.setIconified(false);
                  stage.toFront();
                  stage.requestFocus();
                }));

    viewModel.start();
  }

  @Override
  public void stop() {
    if (service != null) {
      service.close();
    }
  }
}
