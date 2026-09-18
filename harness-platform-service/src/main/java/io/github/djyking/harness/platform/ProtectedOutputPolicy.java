package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Domain-owned projection authorization, independent of account authentication. */
@FunctionalInterface
public interface ProtectedOutputPolicy {
  boolean canRead(
      IdentityProvider.Principal reader, PlatformRepository.Owned owned, JsonNode output);

  static ProtectedOutputPolicy denyAll() {
    return (reader, owned, output) -> false;
  }

  /** Each policy must recognize and fully validate its own provenance before granting access. */
  static ProtectedOutputPolicy anyOf(ProtectedOutputPolicy... policies) {
    var selected = List.of(policies);
    return (reader, owned, output) -> {
      for (var policy : selected) {
        try {
          if (policy.canRead(reader, owned, output)) return true;
        } catch (RuntimeException unavailable) {
          // An unavailable or unknown domain never grants access.
        }
      }
      return false;
    };
  }

  /** Compatibility for embedders using the original identity extension point. */
  static ProtectedOutputPolicy legacyIdentity(IdentityProvider identity) {
    return (reader, owned, output) ->
        OpsAgentProtectedOutputPolicy.exactRetrieval(owned)
            && identity.canReadProtectedOutput(reader, owned, output);
  }
}
