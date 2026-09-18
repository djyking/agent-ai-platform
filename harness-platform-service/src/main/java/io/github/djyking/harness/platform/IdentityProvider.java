package io.github.djyking.harness.platform;

import java.time.Instant;
import java.util.Set;

/** Existing identity authority; credentials remain in request memory and SecretProvider only. */
public interface IdentityProvider {
  record Principal(String application, String project, String subject, Set<String> permissions) {
    public Principal {
      permissions = Set.copyOf(permissions);
    }

    public boolean permits(String permission) {
      return permissions.contains(permission) || permissions.contains("*");
    }

    public String assignment() {
      return application + "/" + subject;
    }

    public String scope() {
      return io.github.djyking.harness.core.Json.hash(
          java.util.List.of(application, project, subject, new java.util.TreeSet<>(permissions)));
    }
  }

  Principal authenticate(String project, String applicationCredential, String userToken);

  String delegate(
      Principal principal,
      String applicationCredential,
      String userToken,
      String runId,
      Instant deadline);

  Principal current(String application, String project, String delegationId, String runId);

  String toolAuthorization(String application, String project, String delegationId, String runId);

  /** Current domain ACL check; false on uncertainty. No credential or result is persisted here. */
  default boolean canReadProtectedOutput(
      Principal reader,
      PlatformRepository.Owned owned,
      com.fasterxml.jackson.databind.JsonNode output) {
    return false;
  }
}
