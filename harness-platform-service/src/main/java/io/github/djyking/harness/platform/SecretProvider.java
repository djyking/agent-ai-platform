package io.github.djyking.harness.platform;

import java.nio.file.*;

/** References are deployment configuration, never Run inputs. No permissive default secrets. */
public interface SecretProvider {
  String resolve(String reference);

  static SecretProvider environment() {
    return ref -> {
      if (ref == null) throw new IllegalStateException("Secret reference required");
      String value;
      if (ref.matches("env:[A-Z][A-Z0-9_]{1,100}")) value = System.getenv(ref.substring(4));
      else if (ref.startsWith("file:")) {
        try {
          value = Files.readString(Path.of(ref.substring(5))).strip();
        } catch (Exception ex) {
          throw new IllegalStateException("Secret file unavailable");
        }
      } else throw new IllegalStateException("Unsupported secret reference");
      if (value == null
          || value.length() < 16
          || value.length() > 8192
          || value.contains("\n")
          || value.contains("\r")) throw new IllegalStateException("Secret missing or invalid");
      return value;
    };
  }
}
