package io.github.djyking.harness.platform;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** An application/user token is intentionally insufficient for a cluster metrics scrape. */
@RestController
public final class PlatformMetricsController {
  private final OperationsService operations;
  private final byte[] authorization;

  public PlatformMetricsController(OperationsService operations, Environment env) {
    this.operations = operations;
    String token = env.getProperty("HARNESS_METRICS_TOKEN");
    if (token != null
        && !token.isBlank()
        && (token.length() < 32
            || token.length() > 512
            || token.chars().anyMatch(Character::isWhitespace)))
      throw new IllegalArgumentException(
          "Dedicated metrics token must contain 32 to 512 non-whitespace characters");
    this.authorization =
        token == null || token.isBlank()
            ? null
            : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
  }

  @GetMapping(value = "/internal/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
  public ResponseEntity<String> scrape(HttpServletRequest request) {
    if (authorization == null) throw ApiFailure.hidden();
    String given = CapabilityHttp.header(request, "Authorization");
    if (given == null
        || !MessageDigest.isEqual(authorization, given.getBytes(StandardCharsets.UTF_8)))
      throw new ApiFailure(401, "UNAUTHENTICATED");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.metrics());
  }
}
