package ui.card;

import model.Media;

public interface CardActions {

  void select(int index);

  void open(Media media, int index);

  void openContainingFolder(Media media);
}
