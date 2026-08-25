package db;

public class DataAccessException extends RuntimeException {
  public DataAccessException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
