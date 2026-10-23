package io.github.djyking.harness.platform;

import static io.github.djyking.harness.platform.PlatformRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.core.Json;
import java.sql.*;
import java.util.*;

/** Requests use immutable capability revisions; admission gates are indexed, shared SQL state. */
public final class OnboardingRepository {
  static final String KIND = "onboarding";
  final PlatformRepository platform;
  final CapabilityRepository records;

  public OnboardingRepository(PlatformRepository platform) {
    this.platform = platform;
    records = new CapabilityRepository(platform);
  }

  public void initialize() {
    if (!platform.bootstrapSchema()) {
      validateSchema();
      return;
    }
    platform.transaction(
        c -> {
          try (var s =
                  prepare(
                      c, "SELECT schema_version FROM harness_schema WHERE component='onboarding'");
              var r = s.executeQuery()) {
            if (r.next() && (r.getInt(1) < 1 || r.getInt(1) > 2))
              throw new IllegalStateException("Unsupported onboarding schema");
          }
          boolean mysql = c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
          String text =
              mysql ? " VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin" : " VARCHAR(128)";
          execute(
              c,
              "CREATE TABLE IF NOT EXISTS onboarding_bindings(target_project"
                  + text
                  + " NOT NULL,application_id"
                  + text
                  + " NOT NULL,sponsor_project"
                  + text
                  + " NOT NULL,request_id"
                  + text
                  + " NOT NULL,enabled INTEGER NOT NULL,PRIMARY KEY(target_project,application_id))"
                  + (mysql ? " ENGINE=InnoDB" : ""));
          execute(
              c,
              "CREATE TABLE IF NOT EXISTS onboarding_requests(sponsor_project"
                  + text
                  + " NOT NULL,request_id"
                  + text
                  + " NOT NULL,requester VARCHAR(300)"
                  + (mysql ? " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin" : "")
                  + " NOT NULL,PRIMARY KEY(sponsor_project,request_id),INDEX"
                  + " onboarding_request_owner_idx(sponsor_project,requester,request_id))"
                  + (mysql ? " ENGINE=InnoDB" : ""));
          try (var s =
                  prepare(
                      c,
                      "SELECT project_id,record_id,document_json FROM capability_records WHERE"
                          + " kind=?",
                      KIND);
              var r = s.executeQuery()) {
            while (r.next()) {
              try (var lookup =
                      prepare(
                          c,
                          "SELECT requester FROM onboarding_requests WHERE sponsor_project=? AND"
                              + " request_id=?",
                          r.getString(1),
                          r.getString(2));
                  var existing = lookup.executeQuery()) {
                String requester = Json.read(r.getString(3)).path("requester").asText();
                if (requester.isBlank())
                  throw new IllegalStateException("Onboarding owner missing");
                if (existing.next()) {
                  if (!requester.equals(existing.getString(1)))
                    throw new IllegalStateException("Onboarding owner projection differs");
                } else
                  execute(
                      c,
                      "INSERT INTO onboarding_requests VALUES(?,?,?)",
                      r.getString(1),
                      r.getString(2),
                      requester);
              }
            }
          }
          try {
            execute(
                c, "INSERT INTO harness_schema(component,schema_version) VALUES('onboarding',2)");
          } catch (SQLException e) {
            if (e.getSQLState() == null || !e.getSQLState().startsWith("23")) throw e;
            execute(
                c,
                "UPDATE harness_schema SET schema_version=2 WHERE component='onboarding' AND"
                    + " schema_version=1");
          }
          return null;
        });
  }

  public void validateSchema() {
    platform.transaction(
        c -> {
          SchemaValidation.require(
              c,
              "onboarding",
              2,
              Map.of(
                  "onboarding_bindings",
                  "target_project,application_id,sponsor_project,request_id,enabled",
                  "onboarding_requests",
                  "sponsor_project,request_id,requester"));
          return null;
        });
  }

  ObjectNode get(Connection c, String sponsor, String id) throws SQLException {
    return records.get(c, sponsor, KIND, id, true);
  }

  void owner(Connection c, ObjectNode request) throws SQLException {
    execute(
        c,
        "INSERT INTO onboarding_requests VALUES(?,?,?)",
        request.path("sponsorProject").asText(),
        request.path("id").asText(),
        request.path("requester").asText());
  }

  void bind(Connection c, ObjectNode request) throws SQLException {
    try {
      execute(
          c,
          "INSERT INTO onboarding_bindings VALUES(?,?,?,?,0)",
          request.path("targetProject").asText(),
          request.path("applicationId").asText(),
          request.path("sponsorProject").asText(),
          request.path("id").asText());
    } catch (SQLException e) {
      if (e.getSQLState() != null && e.getSQLState().startsWith("23"))
        throw new ApiFailure(409, "APPLICATION_ALREADY_MANAGED");
      throw e;
    }
  }

  void enabled(Connection c, ObjectNode request, boolean enabled) throws SQLException {
    if (execute(
            c,
            "UPDATE onboarding_bindings SET enabled=? WHERE target_project=? AND application_id=?"
                + " AND sponsor_project=? AND request_id=?",
            enabled ? 1 : 0,
            request.path("targetProject").asText(),
            request.path("applicationId").asText(),
            request.path("sponsorProject").asText(),
            request.path("id").asText())
        != 1) throw new ApiFailure(409, "ONBOARDING_BINDING_MISSING");
  }

  /**
   * Legacy applications have no gate. Managed applications fail closed until authority
   * verification.
   */
  public void requireAccess(IdentityProvider.Principal principal) {
    platform.transaction(
        c -> {
          try (var s =
                  prepare(
                      c,
                      "SELECT enabled FROM onboarding_bindings WHERE target_project=? AND"
                          + " application_id=?",
                      principal.project(),
                      principal.application());
              var r = s.executeQuery()) {
            if (r.next() && r.getInt(1) != 1) throw new ApiFailure(403, "APPLICATION_NOT_ACTIVE");
          }
          return null;
        });
  }

  JsonNode list(IdentityProvider.Principal principal, String after, int limit, boolean admin) {
    if (limit < 1 || limit > 100 || after == null || after.length() > 128)
      throw ApiFailure.invalid();
    if (!after.isEmpty()) ApiJson.identifier(after);
    return platform.transaction(
        c -> {
          var out = Json.object();
          var items = out.putArray("items");
          String cursor = after;
          int scanned = 0;
          try (var s =
                  prepare(
                      c,
                      "SELECT r.record_id,r.document_json FROM onboarding_requests o"
                          + " JOIN capability_records r ON r.project_id=o.sponsor_project"
                          + " AND r.record_id=o.request_id AND r.kind=?"
                          + " WHERE o.sponsor_project=?"
                          + (admin ? "" : " AND o.requester=?")
                          + " AND o.request_id>? ORDER BY o.request_id LIMIT ?",
                      listArguments(principal, after, limit, admin));
              var r = s.executeQuery()) {
            while (r.next()) {
              if (++scanned > limit) {
                out.put("nextCursor", cursor);
                break;
              }
              cursor = r.getString(1);
              var value = Json.read(r.getString(2));
              items.add(value);
            }
          }
          return out;
        });
  }

  private Object[] listArguments(
      IdentityProvider.Principal principal, String after, int limit, boolean admin) {
    return admin
        ? new Object[] {KIND, principal.project(), after, limit + 1}
        : new Object[] {KIND, principal.project(), principal.assignment(), after, limit + 1};
  }
}
