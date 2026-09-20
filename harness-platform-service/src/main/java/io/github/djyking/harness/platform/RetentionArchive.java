package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Json;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** AES-GCM encryption is deliberately independent of the application signing key. */
public final class RetentionArchive {
  private final byte[] key;
  private final String keyId;
  private final SecureRandom random = new SecureRandom();

  public RetentionArchive(String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      key = null;
      keyId = null;
      return;
    }
    try {
      key = Base64.getDecoder().decode(base64Key);
    } catch (IllegalArgumentException bad) {
      throw new IllegalArgumentException("Retention key must be base64 AES-256 material");
    }
    if (key.length != 32)
      throw new IllegalArgumentException("Retention key must contain exactly 32 bytes");
    keyId = Json.hash(Base64.getEncoder().encodeToString(key)).substring(0, 16);
  }

  public boolean available() {
    return key != null;
  }

  public String keyId() {
    return keyId;
  }

  public String encrypt(String archiveId, JsonNode payload) {
    if (!available()) throw new ApiFailure(409, "RETENTION_ARCHIVE_KEY_REQUIRED");
    if (Json.write(payload).length() > 16_777_216)
      throw new ApiFailure(409, "RETENTION_ARCHIVE_REQUIRES_PARTITION");
    byte[] iv = new byte[12];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      cipher.updateAAD(archiveId.getBytes(StandardCharsets.UTF_8));
      String encrypted =
          Base64.getEncoder().encodeToString(iv)
              + "."
              + Base64.getEncoder()
                  .encodeToString(
                      cipher.doFinal(Json.write(payload).getBytes(StandardCharsets.UTF_8)));
      // Verify before any live row is modified. GCM authenticates both bytes and archive
      // identity.
      if (!Json.hash(payload).equals(Json.hash(decrypt(archiveId, encrypted))))
        throw new IllegalStateException("Archive verification failed");
      return encrypted;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Archive encryption unavailable");
    }
  }

  JsonNode decrypt(String archiveId, String encrypted) {
    if (!available()) throw new IllegalStateException("Archive key unavailable");
    try {
      String[] parts = encrypted.split("\\.", -1);
      if (parts.length != 2) throw new IllegalArgumentException("Invalid archive envelope");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
      cipher.updateAAD(archiveId.getBytes(StandardCharsets.UTF_8));
      return Json.read(
          new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("Archive verification failed");
    }
  }
}
