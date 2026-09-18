package io.github.djyking.harness.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.djyking.harness.core.Json;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConsoleHttpTest {
  private PlatformTestSupport fixture;
  private ConsoleSessions sessions;
  private MockMvc mvc;
  private static final String ORIGIN = "http://localhost";

  @BeforeEach
  void setup() {
    fixture = new PlatformTestSupport();
    sessions =
        new ConsoleSessions(fixture.service, fixture.config, "app-a-credential", fixture.clock, 2);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleController(
                    sessions, new ConsoleIdentityLogin(URI.create("http://127.0.0.1:1"))))
            .setControllerAdvice(new ApiErrors())
            .addFilters(new ConsoleSecurityHeaders())
            .build();
  }

  @AfterEach
  void close() {
    fixture.close();
  }

  private MvcResult exchange() throws Exception {
    return mvc.perform(
            post("/console/session")
                .header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"projectId\":\"project-a\",\"userToken\":\"alice-token\"}"))
        .andExpect(status().isOk())
        .andReturn();
  }

  @Test
  void browserGetsOnlyCookieAndCsrfAndNoUpstreamCredential() throws Exception {
    var result = exchange();
    String body = result.getResponse().getContentAsString();
    String cookie = result.getResponse().getHeader("Set-Cookie");
    assertTrue(cookie.contains("HttpOnly"));
    assertTrue(cookie.contains("SameSite=Strict"));
    assertTrue(cookie.contains("Path=/console"));
    assertFalse(body.contains("alice-token"));
    assertFalse(body.contains("app-a-credential"));
    assertTrue(Json.read(body).path("authenticated").asBoolean());
    assertEquals(2, Json.read(body).path("projects").size());
    assertEquals("DENY", result.getResponse().getHeader("X-Frame-Options"));
  }

  @Test
  void loginRequiresSameOriginAndValidExistingIdentity() throws Exception {
    for (String origin :
        new String[] {"https://evil.invalid", "null", "http://localhost@evil.invalid"})
      mvc.perform(
              post("/console/session")
                  .header("Origin", origin)
                  .contentType("application/json")
                  .content("{\"projectId\":\"project-a\",\"userToken\":\"alice-token\"}"))
          .andExpect(status().isForbidden());
    mvc.perform(post("/console/session").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/console/session")
                .header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"projectId\":\"project-a\",\"userToken\":\"forged\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void cookieAloneCannotMutateAndCallerCredentialsCannotSelectAnotherProject() throws Exception {
    var result = exchange();
    Cookie cookie = result.getResponse().getCookie(ConsoleController.COOKIE);
    String csrf = Json.read(result.getResponse().getContentAsString()).path("csrfToken").asText();
    mvc.perform(
            post("/console/api/projects/project-a/runs")
                .cookie(cookie)
                .header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/console/api/projects/project-b/runs")
                .cookie(cookie)
                .header("Authorization", "Bearer attacker")
                .header("X-Harness-User-Token", "reviewer-token"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/console/api/projects/project-a/runs")
                .cookie(cookie)
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", csrf)
                .contentType("application/json")
                .content("{}"))
        .andExpect(forwardedUrl("/v1/projects/project-a/runs"));
  }

  @Test
  void projectSwitchRotatesCookieAndUsesFreshAuthorityWithoutExposingToken() throws Exception {
    var result = exchange();
    Cookie old = result.getResponse().getCookie(ConsoleController.COOKIE);
    String csrf = Json.read(result.getResponse().getContentAsString()).path("csrfToken").asText();
    var changed =
        mvc.perform(
                post("/console/session")
                    .cookie(old)
                    .header("Origin", ORIGIN)
                    .header("X-CSRF-Token", csrf)
                    .contentType("application/json")
                    .content("{\"projectId\":\"project-b\"}"))
            .andExpect(status().isOk())
            .andReturn();
    Cookie next = changed.getResponse().getCookie(ConsoleController.COOKIE);
    assertNotEquals(old.getValue(), next.getValue());
    mvc.perform(get("/console/session").cookie(old))
        .andExpect(jsonPath("$.authenticated").value(false));
    fixture.identities.grant("app-a", "project-b", "alice", Set.of("runs:read"));
    mvc.perform(get("/console/session").cookie(next))
        .andExpect(jsonPath("$.principal.permissions.length()").value(1));
  }

  @Test
  void logoutAndExpiryInvalidateServerSession() throws Exception {
    var result = exchange();
    Cookie cookie = result.getResponse().getCookie(ConsoleController.COOKIE);
    String csrf = Json.read(result.getResponse().getContentAsString()).path("csrfToken").asText();
    mvc.perform(
            delete("/console/session")
                .cookie(cookie)
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", csrf))
        .andExpect(status().isOk());
    mvc.perform(get("/console/api/projects/project-a/runs").cookie(cookie))
        .andExpect(status().isUnauthorized());
    var next = exchange();
    fixture.clock.advance(java.time.Duration.ofMinutes(31));
    mvc.perform(
            get("/console/session").cookie(next.getResponse().getCookie(ConsoleController.COOKIE)))
        .andExpect(jsonPath("$.authenticated").value(false));
  }

  @Test
  void malformedInputAndUnavailableProviderNeverEchoSecrets() throws Exception {
    mvc.perform(
            post("/console/session")
                .header("Origin", ORIGIN)
                .contentType("application/json")
                .content(
                    "{\"projectId\":\"project-a\",\"userToken\":\"alice-token\",\"permissions\":[\"*\"]}"))
        .andExpect(status().isBadRequest());
    var result =
        mvc.perform(get("/console/auth/captcha"))
            .andExpect(status().isServiceUnavailable())
            .andReturn();
    assertFalse(result.getResponse().getContentAsString().contains("127.0.0.1"));
    sessions.create("project-a", "bob-token", null);
    sessions.create("project-a", "alice-token", null);
    assertEquals(
        429,
        assertThrows(ApiFailure.class, () -> sessions.create("project-a", "alice-token", null))
            .status);
  }

  @Test
  void forwardedRequestReplacesAllCallerAuthorityHeaders() throws Exception {
    var session = sessions.create("project-a", "alice-token", null);
    var forwarded = new java.util.concurrent.atomic.AtomicBoolean();
    var request =
        new org.springframework.mock.web.MockHttpServletRequest(
            "GET", "/console/api/projects/project-a/runs") {
          @Override
          public jakarta.servlet.RequestDispatcher getRequestDispatcher(String path) {
            assertEquals("/v1/projects/project-a/runs", path);
            return new jakarta.servlet.RequestDispatcher() {
              public void include(
                  jakarta.servlet.ServletRequest request,
                  jakarta.servlet.ServletResponse response) {
                fail("BFF must forward");
              }

              public void forward(
                  jakarta.servlet.ServletRequest request,
                  jakarta.servlet.ServletResponse response) {
                var delegated = (jakarta.servlet.http.HttpServletRequest) request;
                assertEquals("Bearer app-a-credential", delegated.getHeader("authorization"));
                assertEquals(
                    java.util.List.of("Bearer app-a-credential"),
                    java.util.Collections.list(delegated.getHeaders("Authorization")));
                assertEquals(
                    java.util.List.of("alice-token"),
                    java.util.Collections.list(delegated.getHeaders("X-Harness-User-Token")));
                forwarded.set(true);
              }
            };
          }
        };
    request.setCookies(new Cookie(ConsoleController.COOKIE, session.id()));
    request.addHeader("Authorization", "Bearer forged-one");
    request.addHeader("Authorization", "Bearer forged-two");
    request.addHeader("X-Harness-User-Token", "reviewer-token");
    new ConsoleController(sessions, new ConsoleIdentityLogin(URI.create("http://127.0.0.1:1")))
        .proxy(request, new org.springframework.mock.web.MockHttpServletResponse());
    assertTrue(forwarded.get());
  }

  @Test
  void duplicateOriginsCookiesAndDisabledLoginFailClosed() throws Exception {
    var result = exchange();
    var cookie = result.getResponse().getCookie(ConsoleController.COOKIE);
    mvc.perform(
            post("/console/session")
                .header("Origin", ORIGIN, "https://evil.invalid")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/console/session")
                .cookie(cookie, new Cookie(ConsoleController.COOKIE, "different-session")))
        .andExpect(status().isBadRequest());
    var disabled = new ConsoleSessions(fixture.service, fixture.config, null, fixture.clock, 2);
    var disabledMvc =
        MockMvcBuilders.standaloneSetup(
                new ConsoleController(
                    disabled, new ConsoleIdentityLogin(URI.create("http://127.0.0.1:1"))))
            .setControllerAdvice(new ApiErrors())
            .build();
    disabledMvc
        .perform(
            post("/console/login")
                .header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("CONSOLE_NOT_CONFIGURED"));
    mvc.perform(
            post("/console/session")
                .secure(true)
                .with(
                    r -> {
                      r.setScheme("https");
                      r.setServerPort(443);
                      return r;
                    })
                .header("Origin", "https://localhost")
                .contentType("application/json")
                .content("{\"projectId\":\"project-a\",\"userToken\":\"bob-token\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Secure")));
  }
}
