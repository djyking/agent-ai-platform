package io.github.djyking.harness.client;

/** Resolves a credential at request time; the SDK never persists or logs the value. */
@FunctionalInterface
public interface SecretProvider {
  String resolve();

  static SecretProvider environment(String name) {
    if (name == null || !name.matches("[A-Z][A-Z0-9_]{0,127}"))
      throw new IllegalArgumentException("Invalid secret environment variable name");
    return () -> System.getenv(name);
  }
}
