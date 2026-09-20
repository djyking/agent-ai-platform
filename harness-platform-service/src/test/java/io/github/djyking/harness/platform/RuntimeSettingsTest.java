package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class RuntimeSettingsTest {
  @Test
  void defaultsRemainCompatibleButDeployedRuntimeCannotBootstrapOrUseLocalIdentity() {
    assertTrue(RuntimeSettings.from(new MockEnvironment()).bootstrapSchema());
    var env =
        new MockEnvironment()
            .withProperty("HARNESS_ENVIRONMENT", "production")
            .withProperty("HARNESS_GOVERNANCE_CONFIG", "private-governance.json");
    assertFalse(RuntimeSettings.from(env).bootstrapSchema());
    env.setProperty("HARNESS_SCHEMA_MODE", "bootstrap");
    assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(env));
    env.setProperty("HARNESS_SCHEMA_MODE", "validate");
    env.setProperty("HARNESS_IDENTITY_PROVIDER", "local-test");
    assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(env));
  }

  @Test
  void forwardedHeadersRequireAnExplicitNativeTrustedProxyBoundary() {
    var env = new MockEnvironment().withProperty("server.forward-headers-strategy", "framework");
    assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(env));
    env.setProperty("server.forward-headers-strategy", "native");
    assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(env));
    env.setProperty("server.tomcat.remoteip.internal-proxies", "127\\.0\\.0\\.1");
    assertDoesNotThrow(() -> RuntimeSettings.from(env));
  }
}
