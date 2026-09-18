package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Json;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProtectedOutputPolicyTest {
  @Test
  void opsCompatibilityRequiresExactUntransformedWorkflowOwnerAndDomainShape() {
    try (var fixture = new PlatformTestSupport()) {
      String id = fixture.id(fixture.create("protected", "policy-exact-test"));
      var owned = fixture.repository.owned(id);
      AtomicInteger calls = new AtomicInteger();
      var policy =
          new OpsAgentProtectedOutputPolicy(
              fixture.identities,
              (auth, citations) -> {
                calls.incrementAndGet();
                assertEquals("Bearer explicitly-test-only", auth);
                return true;
              });
      assertTrue(policy.canRead(fixture.principal(), owned, PlatformTestSupport.protectedResult()));
      assertFalse(
          policy.canRead(
              fixture.principal("app-a", "project-a", "bob"),
              owned,
              PlatformTestSupport.protectedResult()));
      assertFalse(
          policy.canRead(
              fixture.principal(),
              owned,
              ((com.fasterxml.jackson.databind.node.ObjectNode)
                      PlatformTestSupport.protectedResult())
                  .put("extra", "undisclosed")));
      String modelId = fixture.id(fixture.create("protected-model", "policy-model-test"));
      assertFalse(
          policy.canRead(
              fixture.principal(),
              fixture.repository.owned(modelId),
              PlatformTestSupport.protectedResult()));
      assertEquals(1, calls.get());
      fixture.identities.revoke("app-a", "project-a", "alice", "opsagent:rag:search");
      assertFalse(
          policy.canRead(fixture.principal(), owned, PlatformTestSupport.protectedResult()));
      assertEquals(1, calls.get());
    }
  }

  @Test
  void unknownProvenanceAndUnavailablePoliciesNeverGrantByDefault() {
    try (var fixture = new PlatformTestSupport()) {
      String id = fixture.id(fixture.create("protected", "policy-unknown-test"));
      var owned = fixture.repository.owned(id);
      ProtectedOutputPolicy unavailable =
          (reader, run, output) -> {
            throw new IllegalStateException("domain unavailable");
          };
      assertFalse(
          ProtectedOutputPolicy.anyOf(unavailable, ProtectedOutputPolicy.denyAll())
              .canRead(fixture.principal(), owned, Json.object()));
      assertFalse(
          new OpsAgentProtectedOutputPolicy(fixture.identities, (java.net.URI) null)
              .canRead(fixture.principal(), owned, PlatformTestSupport.protectedResult()));
      assertFalse(
          new OpsAgentProtectedOutputPolicy(
                  fixture.identities,
                  (authorization, citations) -> {
                    throw new IllegalStateException();
                  })
              .canRead(fixture.principal(), owned, PlatformTestSupport.protectedResult()));
    }
  }
}
