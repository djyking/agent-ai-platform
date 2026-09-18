package io.github.djyking.harness.platform;

import io.github.djyking.harness.core.Json;
import java.net.URI;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
public class PlatformApplication {
  public static void main(String[] args) {
    if (args.length == 2 && args[0].equals("import-evidence")) {
      try {
        EvidenceImport.run(Path.of(args[1]));
      } catch (Exception ex) {
        System.err.println("Verified evidence import failed; no receipt content is logged");
        System.exit(1);
      }
      return;
    }
    if (args.length == 2 && args[0].equals("releases")) {
      Deployment deployment = Deployment.load(Path.of(args[1]));
      var list = Json.MAPPER.createArrayNode();
      for (var release : deployment.releases()) {
        var row = Json.object().put("projectId", release.projectId());
        row.set("releaseRef", release.reference());
        list.add(row);
      }
      System.out.println(Json.write(list));
      return;
    }
    SpringApplication.run(PlatformApplication.class, args);
  }

  @Bean(destroyMethod = "close")
  public PlatformRuntime runtime(DataSource dataSource, Environment env) {
    String path = env.getProperty("HARNESS_CONFIG");
    if (path == null || path.isBlank())
      throw new IllegalStateException("HARNESS_CONFIG is required");
    Deployment deployment = Deployment.load(Path.of(path));
    SecretProvider secrets = SecretProvider.environment();
    URI ragOrigin =
        deployment.tools().stream()
            .filter(t -> t.path("kind").asText().equals("opsagent-rag"))
            .findFirst()
            .map(t -> URI.create(t.path("origin").asText()))
            .orElse(null);
    return new PlatformRuntime(
        dataSource,
        deployment,
        secrets,
        new OpsAgentIdentityProvider(
            URI.create(deployment.identityOrigin()),
            ragOrigin,
            deployment.applicationSecrets(),
            secrets));
  }

  @Bean
  public PlatformService platformService(PlatformRuntime runtime) {
    return runtime.service;
  }

  @Bean
  public CatalogService catalogService(PlatformRuntime runtime) {
    return runtime.catalog;
  }

  @Bean
  public ApplicationRunner workerStart(PlatformRuntime runtime) {
    return args -> runtime.start();
  }

  @RestController
  public static class Health {
    private final DataSource dataSource;

    public Health(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public java.util.Map<String, String> health() {
      try (var c = dataSource.getConnection();
          var s = c.createStatement();
          var r = s.executeQuery("SELECT 1")) {
        r.next();
        return java.util.Map.of("status", "UP");
      } catch (java.sql.SQLException ex) {
        throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
      }
    }
  }
}
