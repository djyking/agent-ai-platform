package io.github.djyking.harness.platform;

import io.github.djyking.harness.core.Json;
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
    if (args.length == 2 && args[0].equals("onboarding-apply")) {
      try {
        OnboardingProvisioner.run(
            Path.of(args[1]), new org.springframework.core.env.StandardEnvironment());
      } catch (Exception ex) {
        System.err.println("Onboarding apply failed; credentials and details are not logged");
        System.exit(1);
      }
      return;
    }
    if (args.length == 2 && args[0].equals("resolve-permit")) {
      try {
        PermitResolution.run(
            Path.of(args[1]), new org.springframework.core.env.StandardEnvironment());
      } catch (Exception ex) {
        System.err.println("Permit resolution failed; no evidence content is logged");
        System.exit(1);
      }
      return;
    }
    if (args.length == 1 && args[0].equals("migrate")) {
      try {
        SchemaMigration.run(new org.springframework.core.env.StandardEnvironment());
      } catch (Exception ex) {
        System.err.println(
            "Schema migration failed; credentials and database details are not logged");
        System.exit(1);
      }
      return;
    }
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

  @Bean
  public Deployment deployment(Environment env) {
    String path = env.getProperty("HARNESS_CONFIG");
    if (path == null || path.isBlank())
      throw new IllegalStateException("HARNESS_CONFIG is required");
    return Deployment.load(Path.of(path));
  }

  @Bean
  public SecretProvider secrets() {
    return SecretProvider.environment();
  }

  @Bean
  public IdentityProviders.Bundle identityProviders(
      Deployment deployment, SecretProvider secrets, Environment env) {
    RuntimeSettings.from(env);
    return IdentityProviders.create(deployment, secrets, env);
  }

  @Bean(destroyMethod = "close")
  public PlatformRuntime runtime(
      DataSource dataSource,
      Deployment deployment,
      SecretProvider secrets,
      IdentityProviders.Bundle identity,
      Environment env) {
    RuntimeSettings settings = RuntimeSettings.from(env);
    return new PlatformRuntime(
        dataSource,
        deployment,
        secrets,
        identity.identity(),
        identity.outputPolicy(),
        settings.bootstrapSchema(),
        GovernanceSettings.load(env),
        env.getProperty("HARNESS_IDENTITY_PROVIDER", "opsagent"));
  }

  @Bean
  public OnboardingService onboardingService(PlatformRuntime runtime) {
    return runtime.onboarding;
  }

  @Bean
  public OperationsService operationsService(PlatformRuntime runtime) {
    return runtime.operations;
  }

  @Bean
  public RetentionService retentionService(PlatformRuntime runtime, Environment env) {
    return new RetentionService(
        runtime.repository, new RetentionArchive(env.getProperty("HARNESS_RETENTION_KEY")));
  }

  @Bean
  public StudioService studioService(PlatformRuntime runtime) {
    return runtime.studio;
  }

  @Bean
  public KnowledgeService knowledgeService(PlatformRuntime runtime) {
    return runtime.knowledge;
  }

  @Bean
  public CapabilityService capabilityService(PlatformRuntime runtime) {
    return runtime.capabilities;
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
    private final PlatformRuntime runtime;

    public Health(PlatformRuntime runtime) {
      this.runtime = runtime;
    }

    @GetMapping("/health/live")
    public java.util.Map<String, String> live() {
      return java.util.Map.of("status", "UP");
    }

    @GetMapping({"/health", "/health/ready"})
    public java.util.Map<String, String> health() {
      if (!runtime.operations.health() || !runtime.governance.ready(true))
        throw new ApiFailure(503, "TEMPORARILY_UNAVAILABLE");
      return java.util.Map.of("status", "UP");
    }
  }
}
