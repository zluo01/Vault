import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import service.AppConfig;
import service.LibraryService;
import ui.MainView;
import ui.ThemeManager;
import ui.Toast;
import ui.shell.WindowResizer;
import viewmodel.LibraryViewModel;

public class VaultApplication extends Application {

  private LibraryService service;

  @Override
  public void start(Stage stage) {
    AppConfig config = AppConfig.resolveDefault();
    service = new LibraryService(config);

    Toast toast = new Toast();
    LibraryViewModel viewModel = new LibraryViewModel(service, toast, getHostServices());
    ThemeManager themes = new ThemeManager(service);
    MainView mainView = new MainView(viewModel, stage, toast, themes);
    toast.attachTo(mainView.getRoot());

    Scene scene = new Scene(mainView.getRoot(), 1180, 760);
    scene.getStylesheets().add(getClass().getResource("/css/app.css").toExternalForm());
    themes.attach(scene);

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
    SingleInstance.release();
    if (service != null) {
      service.close();
    }
  }
}
