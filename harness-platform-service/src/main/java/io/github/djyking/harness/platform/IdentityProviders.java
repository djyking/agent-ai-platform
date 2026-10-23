package io.github.djyking.harness.platform;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.core.env.Environment;

/**
 * Explicit identity adapter selection. Ops remains the compatible default for existing installs.
 */
public final class IdentityProviders {
  private IdentityProviders() {}

  public record Bundle(
      IdentityProvider identity, IdentityLogin login, ProtectedOutputPolicy outputPolicy) {}

  public static Bundle create(
      Deployment deployment, SecretProvider secrets, Environment environment) {
    return switch (environment.getProperty("HARNESS_IDENTITY_PROVIDER", "opsagent")) {
      case "opsagent" -> {
        URI origin = URI.create(deployment.identityOrigin());
        URI ragOrigin =
            deployment.tools().stream()
                .filter(t -> t.path("kind").asText().equals("opsagent-rag"))
                .findFirst()
                .map(t -> URI.create(t.path("origin").asText()))
                .orElse(null);
        var identity =
            new OpsAgentIdentityProvider(
                origin, ragOrigin, deployment.applicationSecrets(), secrets);
        yield new Bundle(
            identity,
            new ConsoleIdentityLogin(origin),
            new OpsAgentProtectedOutputPolicy(identity, ragOrigin));
      }
      case "local-test" -> {
        String path = environment.getProperty("HARNESS_LOCAL_IDENTITY_CONFIG");
        String state = environment.getProperty("HARNESS_LOCAL_IDENTITY_STATE");
        if (path == null || path.isBlank() || state == null || state.isBlank())
          throw new IllegalArgumentException(
              "Local test identity requires explicit config and state paths");
        var identity =
            new LocalTestIdentityProvider(
                deployment,
                secrets,
                Path.of(path),
                Path.of(state),
                "true".equals(environment.getProperty("HARNESS_ALLOW_LOCAL_TEST_IDENTITY")),
                environment.getProperty("server.address", ""));
        yield new Bundle(identity, identity, ProtectedOutputPolicy.denyAll());
      }
      default -> throw new IllegalArgumentException("Unsupported identity provider");
    };
  }
}
