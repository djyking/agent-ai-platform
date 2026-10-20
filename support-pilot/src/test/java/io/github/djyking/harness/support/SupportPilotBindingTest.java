package io.github.djyking.harness.support;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupportPilotBindingTest {
  @Test
  void existingConfigurationKeepsLoopbackListener() throws Exception {
    var configuration =
        new SupportPilot.Configuration(
            URI.create("http://127.0.0.1:8097"),
            "support-pilot",
            0,
            () -> "application-secret",
            () -> "operator-token",
            "synthetic-local-access-code-at-least-24",
            null,
            null);
    try (var app = new SupportPilot(configuration)) {
      app.start();
      assertEquals("127.0.0.1", configuration.bindAddress());
      assertTrue(app.listenAddress().getAddress().isLoopbackAddress());
      assertEquals(200, request(app, "GET", "/", app.origin().getRawAuthority(), null));
    }
  }

  @Test
  void containerListenerKeepsExactPublicHostAndOriginChecks() throws Exception {
    try (var app = new SupportPilot(configuration("0.0.0.0", "https://support.example.test"))) {
      app.start();
      assertTrue(app.listenAddress().getAddress().isAnyLocalAddress());
      assertEquals(200, request(app, "GET", "/", "support.example.test", null));
      assertEquals(400, request(app, "GET", "/", "untrusted.example.test", null));
      assertEquals(
          403,
          request(
              app, "POST", "/api/session", "support.example.test", "http://support.example.test"));
    }
  }

  @Test
  void rejectsHostnamesUrlsPortsAndMalformedAddresses() {
    for (String address :
        List.of(
            "",
            "localhost",
            "support.example.test",
            "http://127.0.0.1",
            "127.0.0.1:8098",
            " 0.0.0.0",
            "256.0.0.1",
            "127.1",
            "0127.0.0.1",
            "[::1]",
            "fe80::1%eth0",
            "1:2:3")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> configuration(address, "https://support.example.test"),
          address);
    }
    assertThrows(
        IllegalArgumentException.class, () -> configuration(null, "https://support.example.test"));
  }

  @Test
  void nonLoopbackRequiresHttpsOriginAndRealIdentity() {
    assertThrows(IllegalArgumentException.class, () -> configuration("0.0.0.0", null));
    assertThrows(
        IllegalArgumentException.class, () -> configuration("0.0.0.0", "http://127.0.0.1"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SupportPilot.Configuration(
                URI.create("http://127.0.0.1:8097"),
                "support-pilot",
                0,
                () -> "application-secret",
                () -> "operator-token",
                "synthetic-local-access-code-at-least-24",
                null,
                null,
                "development",
                "local-test",
                null,
                null,
                "0.0.0.0"));
  }

  private static SupportPilot.Configuration configuration(String address, String publicOrigin) {
    return new SupportPilot.Configuration(
        URI.create("https://platform.example.test"),
        "support-pilot",
        0,
        () -> "application-secret",
        null,
        null,
        null,
        null,
        "development",
        "opsagent",
        URI.create("https://identity.example.test"),
        publicOrigin == null ? null : URI.create(publicOrigin),
        address);
  }

  private static int request(
      SupportPilot app, String method, String path, String host, String origin) throws Exception {
    try (var socket = new Socket("127.0.0.1", app.listenAddress().getPort())) {
      socket.setSoTimeout(3000);
      String request =
          method
              + " "
              + path
              + " HTTP/1.1\r\nHost: "
              + host
              + "\r\nConnection: close\r\n"
              + (origin == null ? "" : "Origin: " + origin + "\r\n")
              + (method.equals("POST")
                  ? "Content-Type: application/json\r\nContent-Length: 2\r\n"
                  : "")
              + "\r\n"
              + (method.equals("POST") ? "{}" : "");
      socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
      var reader =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      return Integer.parseInt(reader.readLine().split(" ")[1]);
    }
  }
}
