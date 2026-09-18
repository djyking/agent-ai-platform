package io.github.djyking.harness.platform;

import java.net.URI;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class ConsoleConfiguration implements WebMvcConfigurer {
  @Bean
  public PlatformTrace platformTrace(PlatformRuntime runtime) {
    return runtime.traces;
  }

  @Bean
  public ConsoleSessions consoleSessions(PlatformRuntime runtime, Environment environment) {
    String application = environment.getProperty("HARNESS_CONSOLE_APPLICATION", "platform-console");
    String reference = runtime.deployment.applicationSecrets().get(application);
    return new ConsoleSessions(
        runtime.service,
        runtime.deployment,
        reference == null ? null : SecretProvider.environment().resolve(reference),
        Clock.systemUTC(),
        1024);
  }

  @Bean
  public ConsoleIdentityLogin consoleIdentityLogin(PlatformRuntime runtime) {
    return new ConsoleIdentityLogin(URI.create(runtime.deployment.identityOrigin()));
  }

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addRedirectViewController("/", "/console/");
    registry.addRedirectViewController("/console", "/console/");
    registry.addViewController("/console/").setViewName("forward:/console/index.html");
  }
}
