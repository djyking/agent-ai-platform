package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Server-authenticated cursors and representation-bound strong ETags; one persisted host secret.
 */
public final class SignedTokens {
  private final byte[] key;

  public SignedTokens(String secret) {
    key = secret.getBytes(StandardCharsets.UTF_8);
    if (key.length < 32)
      throw new IllegalArgumentException("Signing key must have at least 32 bytes");
  }

  public String sign(JsonNode value) {
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(Json.write(value).getBytes(StandardCharsets.UTF_8));
    return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
  }

  public JsonNode verify(String value, String error) {
    try {
      if (value == null || value.length() > 4096) throw new IllegalArgumentException();
      String[] parts = value.split("\\.", -1);
      if (parts.length != 2
          || !MessageDigest.isEqual(mac(parts[0]), Base64.getUrlDecoder().decode(parts[1])))
        throw new IllegalArgumentException();
      return ApiJson.MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
    } catch (Exception ex) {
      throw new ApiFailure(error.startsWith("PRECONDITION") ? 412 : 400, error);
    }
  }

  private byte[] mac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
    } catch (Exception ex) {
      throw new IllegalStateException("Signing unavailable");
    }
  }
}
