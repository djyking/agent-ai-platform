package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.RunStatus;
import io.github.djyking.harness.core.Json;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlatformProtectedOutputTest {
  @Test
  void ownerSeesExactRetrievalOnlyWhileEveryCitationRemainsAuthorized() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("protected", "protected-owner01"));
      assertEquals(RunStatus.COMPLETED, f.drive(id).status);
      Set<Long> authorized = new HashSet<>(Set.of(11L, 22L));
      f.identities.protectedRead =
          (reader, owned, output) -> {
            assertEquals(f.principal(), reader);
            assertEquals(id, owned.runId());
            assertEquals(protectedResult(), output);
            for (var citation : output.path("citations"))
              if (!authorized.contains(citation.path("documentId").asLong())) return false;
            return true;
          };
      var readable = f.service.get(f.principal(), id);
      ApiJson.validate("RunView", readable.body());
      assertEquals("AVAILABLE", readable.body().path("output").path("visibility").asText());
      assertEquals(protectedResult(), readable.body().path("output").path("value"));
      authorized.remove(22L);
      var revoked = f.service.get(f.principal(), id);
      assertEquals(Json.object().put("visibility", "OMITTED"), revoked.body().path("output"));
      assertNotEquals(readable.etag(), revoked.etag());
      assertEquals(2, f.identities.protectedChecks.get());
      assertEquals(
          protectedResult(),
          f.store.get(id).output,
          "Access projection must not erase the durable result");
    }
  }

  @Test
  void administrationDoesNotAuthorizeAnotherSubjectsProtectedOutput() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("protected", "protected-admin01"));
      f.drive(id);
      f.identities.protectedRead = (reader, owned, output) -> true;
      Set<String> administrator = new HashSet<>(OWNER_PERMISSIONS);
      administrator.add("runs:admin");
      f.identities.grant("app-a", "project-a", "bob", administrator);
      f.identities.grant("app-b", "project-a", "alice", administrator);
      for (var reader :
          List.of(
              f.principal("app-a", "project-a", "bob"),
              f.principal("app-b", "project-a", "alice"))) {
        var view = f.service.get(reader, id);
        ApiJson.validate("RunView", view.body());
        assertEquals(id, view.body().path("id").asText());
        assertEquals(Json.object().put("visibility", "OMITTED"), view.body().path("output"));
      }
      assertEquals(
          0,
          f.identities.protectedChecks.get(),
          "A foreign administrator must never use the original owner's delegated ACL");
    }
  }

  @Test
  void aModelWorkflowCannotExposeProtectedOutputJustBecauseItContainsCitations() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("protected-model", "protected-model1"));
      assertEquals(RunStatus.COMPLETED, f.drive(id).status);
      assertEquals(1, f.modelCalls.get());
      assertTrue(f.store.get(id).output.path("citations").isArray());
      f.identities.protectedRead = (reader, owned, output) -> true;
      var view = f.service.get(f.principal(), id);
      assertEquals(Json.object().put("visibility", "OMITTED"), view.body().path("output"));
      assertEquals(0, f.identities.protectedChecks.get());
    }
  }

  @Test
  void aclProjectionChangesInvalidateEtagsWithoutChangingTheRunRevisionOrPrincipal() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("protected", "protected-etag01"));
      f.drive(id);
      f.identities.protectedRead = (reader, owned, output) -> true;
      var principal = f.principal();
      var view = f.service.get(principal, id);
      long revision = f.store.get(id).revision;
      f.identities.protectedRead = (reader, owned, output) -> false;
      assertEquals(principal, f.principal());
      error(
          412,
          "PRECONDITION_FAILED",
          () ->
              f.service.control(
                  principal, id, "pause", "protected-oldtag", view.etag(), Json.object()));
      assertEquals(revision, f.store.get(id).revision);
      assertEquals(1, f.count("platform_commands"));
      var withheld = f.service.get(principal, id);
      assertNotEquals(view.etag(), withheld.etag());
      error(
          409,
          "INVALID_STATE",
          () ->
              f.service.control(
                  principal, id, "pause", "protected-newtag", withheld.etag(), Json.object()));
    }
  }

  @Test
  void missingOutputPermissionAndUncertainDomainChecksFailClosed() {
    try (var f = new PlatformTestSupport()) {
      String id = f.id(f.create("protected", "protected-fail01"));
      f.drive(id);
      f.identities.protectedRead =
          (reader, owned, output) -> {
            throw new IllegalStateException("simulated domain ACL outage");
          };
      assertEquals(
          "OMITTED",
          f.service.get(f.principal(), id).body().path("output").path("visibility").asText());
      assertEquals(1, f.identities.protectedChecks.get());
      f.identities.protectedRead = (reader, owned, output) -> true;
      f.identities.revoke("app-a", "project-a", "alice", "runs:output:read");
      assertEquals(
          "OMITTED",
          f.service.get(f.principal(), id).body().path("output").path("visibility").asText());
      assertEquals(1, f.identities.protectedChecks.get());
    }
  }
}
