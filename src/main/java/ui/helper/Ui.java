package ui.helper;

import javafx.animation.Interpolator;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.stage.PopupWindow;

/** Small factory helpers shared by every Vault view: styled labels and stroked SVG icons. */
public final class Ui {

  /** The small downward chevron drawn on dropdown triggers. */
  public static final String CHEVRON = "M2.5 4.5 L6 8 L9.5 4.5";

  /** ease-out: fast start, gentle settle — for entrances and hover motion. */
  public static final Interpolator EASE_OUT = Interpolator.SPLINE(0.16, 1, 0.3, 1);

  /** ease-in-out: for reversible motion like the sidebar collapse. */
  public static final Interpolator EASE_IN_OUT = Interpolator.SPLINE(0.65, 0, 0.35, 1);

  private Ui() {}

  public static Label label(String text, String styleClass) {
    Label label = new Label(text);
    label.getStyleClass().add(styleClass);
    return label;
  }

  public static Button button(String text, String styleClass) {
    Button button = new Button(text);
    button.getStyleClass().add(styleClass);
    return button;
  }

  /** Graphic-only button (icon or composed row content); padding comes from the caller/CSS. */
  public static Button button(Node graphic, String styleClass) {
    Button button = new Button();
    button.setGraphic(graphic);
    button.getStyleClass().add(styleClass);
    return button;
  }

  /**
   * Stretch a row-style button's graphic to the button's inner width, so full-width rows (sidebar
   * items, episode rows) keep laying out their content across the whole line.
   */
  public static void stretchGraphic(Button button, Region graphic) {
    button.setMaxWidth(Double.MAX_VALUE);
    graphic
        .prefWidthProperty()
        .bind(
            Bindings.createDoubleBinding(
                () ->
                    button.getWidth()
                        - button.getPadding().getLeft()
                        - button.getPadding().getRight(),
                button.widthProperty(),
                button.paddingProperty()));
  }

  /**
   * Make {@code trigger} open and close {@code popup}, flipping {@code chevron} while it is open.
   *
   * <p>Auto-hide fires on the press, before the trigger's action runs, so a click meant to close
   * the popup would otherwise re-open it at once. An action that arrives right after a hide is
   * therefore taken as that closing click.
   *
   * @param show populates and shows the popup
   */
  public static void togglePopup(Button trigger, PopupWindow popup, Node chevron, Runnable show) {
    long[] hiddenAt = {0};
    trigger.setOnAction(e -> {
      if (popup.isShowing() || System.currentTimeMillis() - hiddenAt[0] < 200) {
        popup.hide();
        return;
      }
      chevron.setRotate(180);
      show.run();
    });
    popup.setOnHidden(e -> {
      chevron.setRotate(0);
      hiddenAt[0] = System.currentTimeMillis();
    });
  }

  public static SVGPath ico(String d, double width) {
    return ico(d, width, "ico");
  }

  public static SVGPath ico(String d, double width, String styleClass) {
    SVGPath path = new SVGPath();
    path.setContent(d);
    path.getStyleClass().add(styleClass);
    path.setFill(Color.TRANSPARENT);
    path.setStrokeWidth(width);
    path.setStrokeLineCap(StrokeLineCap.ROUND);
    path.setStrokeLineJoin(StrokeLineJoin.ROUND);
    return path;
  }

  /** Uppercase micro-label above a form field, stacked with the control. */
  public static VBox fieldBlock(String labelText, Node control) {
    Label label = label(labelText.toUpperCase(), "field-label");
    VBox.setMargin(label, new Insets(0, 0, 9, 0));
    return new VBox(0, label, control);
  }
}
