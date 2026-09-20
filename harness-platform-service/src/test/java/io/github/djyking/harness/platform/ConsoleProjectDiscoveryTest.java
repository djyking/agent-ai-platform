package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.djyking.harness.core.Json;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConsoleProjectDiscoveryTest {
  private MockMvc mvc;
  private ConsoleSessions sessions;
  private PlatformRuntime runtime;
  private final AtomicInteger failedStatus = new AtomicInteger(403);
  private final AtomicInteger loginCalls = new AtomicInteger();
  private final String token = "synthetic-server-held-user-token";

  @BeforeEach
  void setup() {
    var deployment =
        new Deployment(
            "https://identity.invalid",
            "env:SIGN",
            Map.of("console", "env:APP"),
            List.of(
                new Deployment.Project(
                    "hidden-first-project", Set.of("console"), 10, 10000, 10, 10000, 1),
                new Deployment.Project("studio", Set.of("console"), 10, 10000, 10, 10000, 1)),
            List.of(),
            Set.of(),
            List.of(),
            List.of(),
            1,
            10000,
            false);
    // Exercise the actual PlatformService authentication boundary. The explicit identity
    // fixture avoids Mockito's JVM-agent self-attachment and fails on unexpected execution.
    IdentityProvider authority =
        new IdentityProvider() {
          public Principal authenticate(
              String project, String applicationCredential, String userToken) {
            if (!applicationCredential.equals("synthetic-app-credential")
                || !userToken.equals(token)) throw new ApiFailure(401, "UNAUTHENTICATED");
            if (project.equals("hidden-first-project"))
              throw new ApiFailure(failedStatus.get(), "AUTHORITY_REJECTED");
            if (!project.equals("studio")) throw ApiFailure.hidden();
            return new Principal("console", "studio", "alice", Set.of("runs:read"));
          }

          public String delegate(
              Principal principal,
              String applicationCredential,
              String userToken,
              String runId,
              Instant deadline) {
            throw new AssertionError("Project discovery must not delegate execution");
          }

          public Principal current(
              String application, String project, String delegationId, String runId) {
            throw new AssertionError("Project discovery must not inspect a delegated execution");
          }

          public String toolAuthorization(
              String application, String project, String delegationId, String runId) {
            throw new AssertionError("Project discovery must not authorize tools");
          }
        };
    var data = new JdbcDataSource();
    data.setURL(
        "jdbc:h2:mem:console-discovery-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    data.setUser("synthetic-console");
    data.setPassword("synthetic-console-password");
    runtime =
        new PlatformRuntime(
            data,
            deployment,
            reference ->
                reference.equals("env:APP")
                    ? "synthetic-app-credential"
                    : "synthetic-discovery-only-signing-key-0123456789",
            authority);
    sessions =
        new ConsoleSessions(
            runtime.service, deployment, "synthetic-app-credential", Clock.systemUTC(), 2);
    var local =
        new IdentityLogin() {
          public boolean captchaRequired() {
            return false;
          }

          public JsonNode captcha() {
            return Json.object().put("required", false);
          }

          public JsonNode configuration() {
            return Json.object()
                .put("provider", "local-test")
                .put("localTestOnly", true)
                .put("captchaRequired", false);
          }

          public String login(JsonNode input) {
            loginCalls.incrementAndGet();
            assertEquals(Set.of("username", "password"), new HashSet<>(toList(input.fieldNames())));
            return token;
          }
        };
    mvc =
        MockMvcBuilders.standaloneSetup(new ConsoleController(sessions, local))
            .setControllerAdvice(new ApiErrors())
            .build();
  }

  @AfterEach
  void close() {
    if (runtime != null) runtime.close();
  }

  private static List<String> toList(Iterator<String> values) {
    var result = new ArrayList<String>();
    values.forEachRemaining(result::add);
    return result;
  }

  @Test
  void serverDiscoversOnlyAuthorizedProjectsAfterLoginWithoutDefaultBusinessIdOrTokenDisclosure()
      throws Exception {
    mvc.perform(get("/console/session")).andExpect(jsonPath("$.projects.length()").value(0));
    mvc.perform(get("/console/auth/config"))
        .andExpect(jsonPath("$.provider").value("local-test"))
        .andExpect(jsonPath("$.localTestOnly").value(true));
    var response =
        mvc.perform(
                post("/console/login")
                    .header("Origin", "http://localhost")
                    .contentType("application/json")
                    .content("{\"username\":\"alice\",\"password\":\"synthetic-password\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.principal.project").value("studio"))
            .andExpect(jsonPath("$.projects.length()").value(1))
            .andReturn()
            .getResponse();
    assertFalse(response.getContentAsString().contains(token));
    assertFalse(response.getContentAsString().contains("synthetic-app-credential"));
    assertFalse(response.getContentAsString().contains("hidden-first-project"));
    assertTrue(response.getHeader("Set-Cookie").contains("HttpOnly"));
    assertEquals(1, loginCalls.get());
  }

  @Test
  void explicitUnauthorizedProjectCannotFallBackAndInvalidTypesAreRejectedBeforeProviderLogin()
      throws Exception {
    mvc.perform(
            post("/console/login")
                .header("Origin", "http://localhost")
                .contentType("application/json")
                .content(
                    "{\"projectId\":\"hidden-first-project\",\"username\":\"alice\",\"password\":\"synthetic-password\"}"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Set-Cookie"));
    int calls = loginCalls.get();
    mvc.perform(
            post("/console/login")
                .header("Origin", "http://localhost")
                .contentType("application/json")
                .content(
                    "{\"projectId\":{},\"username\":\"alice\",\"password\":\"synthetic-password\"}"))
        .andExpect(status().isBadRequest());
    assertEquals(calls, loginCalls.get());
  }

  @Test
  void discoveryFailsClosedOnRateLimitOrUnavailableAuthorityAndDoesNotSilentlyPickAnotherProject()
      throws Exception {
    for (int status : List.of(429, 503)) {
      failedStatus.set(status);
      mvc.perform(
              post("/console/session")
                  .header("Origin", "http://localhost")
                  .contentType("application/json")
                  .content(Json.write(Json.object().put("userToken", token))))
          .andExpect(status().is(status))
          .andExpect(header().doesNotExist("Set-Cookie"));
    }
    failedStatus.set(403);
    mvc.perform(
            post("/console/session")
                .header("Origin", "http://localhost")
                .contentType("application/json")
                .content(Json.write(Json.object().put("userToken", token))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.principal.project").value("studio"));
    assertEquals(
        "NO_AUTHORIZED_PROJECT",
        assertThrows(ApiFailure.class, () -> sessions.create("", "invalid-token", null)).code);
  }
}
