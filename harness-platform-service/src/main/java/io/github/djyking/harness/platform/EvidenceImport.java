package io.github.djyking.harness.platform;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.djyking.harness.core.Json;
import io.github.djyking.harness.storage.jdbc.JdbcRunStore;
import java.nio.file.Files;
import java.nio.file.Path;

/** Privileged offline import of a receipt already checked by an independent verifier. */
final class EvidenceImport {
  private EvidenceImport() {}

  static void run(Path file) throws Exception {
    if (Files.size(file) > 65536) throw new IllegalArgumentException("Evidence too large");
    var mapper = ApiJson.MAPPER.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    var evidence = mapper.readValue(Files.readAllBytes(file), PlatformRepository.Evidence.class);
    ApiJson.identifier(evidence.id());
    ApiJson.identifier(evidence.project());
    ApiJson.uuid(evidence.runId());
    if (evidence.verifiedAt() <= 0
        || evidence.verifiedAt() > System.currentTimeMillis()
        || evidence.verifier() == null
        || evidence.verifier().length() > 128
        || evidence.result() == null
        || evidence.result().output() == null)
      throw new IllegalArgumentException("Invalid verified receipt");
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(required("HARNESS_JDBC_URL"));
    config.setUsername(required("HARNESS_JDBC_USER"));
    config.setPassword(required("HARNESS_JDBC_PASSWORD"));
    config.setMaximumPoolSize(1);
    try (HikariDataSource dataSource = new HikariDataSource(config)) {
      // Do not bootstrap or migrate while performing a privileged import.
      var repository = new PlatformRepository(new JdbcRunStore(dataSource));
      repository.importVerified(evidence);
      System.out.println(
          Json.write(Json.object().put("evidenceRef", evidence.id()).put("imported", true)));
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Required environment missing: " + name);
    return value;
  }
}
