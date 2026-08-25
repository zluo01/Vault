package ui.helper;

import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

public final class CoverHelper {

  private CoverHelper() {}

  public static void applyCover(ImageView view, Image image, double fitWidth, double fitHeight) {
    view.setFitWidth(fitWidth);
    view.setFitHeight(fitHeight);
    view.setPreserveRatio(false);
    view.setImage(image);
    updateCoverViewport(view, fitWidth, fitHeight);
  }

  public static void updateCoverViewport(ImageView view, double fitWidth, double fitHeight) {
    Image image = view.getImage();
    if (image == null
        || image.getWidth() <= 0
        || image.getHeight() <= 0
        || fitWidth <= 0
        || fitHeight <= 0) {
      if (view.getViewport() != null) {
        view.setViewport(null);
      }
      return;
    }

    final Rectangle2D viewport = getViewport(fitWidth, fitHeight, image);
    // Only assign when the crop actually changed; a fresh Rectangle2D every call invalidates
    // layout.
    if (!viewport.equals(view.getViewport())) {
      view.setViewport(viewport);
    }
  }

  private static Rectangle2D getViewport(double fitWidth, double fitHeight, Image image) {
    double imageRatio = image.getWidth() / image.getHeight();
    double targetRatio = fitWidth / fitHeight;
    double cropWidth = image.getWidth();
    double cropHeight = image.getHeight();

    if (imageRatio > targetRatio) {
      cropWidth = cropHeight * targetRatio;
    } else if (imageRatio < targetRatio) {
      cropHeight = cropWidth / targetRatio;
    }

    double x = Math.max(0, (image.getWidth() - cropWidth) / 2);
    double y = Math.max(0, (image.getHeight() - cropHeight) / 2);
    return new Rectangle2D(x, y, cropWidth, cropHeight);
  }
}
