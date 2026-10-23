package io.github.djyking.harness.storage.jdbc;

/**
 * A storage/configuration failure. Retrying an external tool is never implied by this exception.
 */
public final class JdbcStorageException extends RuntimeException {
  public JdbcStorageException(String message) {
    super(message);
  }

  public JdbcStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
