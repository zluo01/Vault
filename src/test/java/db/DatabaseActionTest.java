package db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseActionTest {

  @ParameterizedTest
  @EnumSource(DatabaseAction.class)
  void everyActionHasAQuery(DatabaseAction action) {
    assertFalse(action.query().isBlank());
    assertEquals(DatabaseAction.load("queries/" + action.name() + ".sql"), action.query());
  }

  @ParameterizedTest
  @ValueSource(strings = {"db/empty.sql", "db/blank.sql"})
  void aQueryFileWithNothingInItIsRejected(String path) {
    assertThrows(IllegalStateException.class, () -> DatabaseAction.load(path));
  }

  @Test
  void aMissingQueryFileIsRejected() {
    assertThrows(NullPointerException.class, () -> DatabaseAction.load("db/missing.sql"));
  }
}
