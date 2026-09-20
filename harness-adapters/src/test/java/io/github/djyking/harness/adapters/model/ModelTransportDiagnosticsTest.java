package io.github.djyking.harness.adapters.model;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class ModelTransportDiagnosticsTest {
  @Test
  void unwrapsAsyncFailuresIntoFixedCategoriesWithoutLeakingMessages() {
    var failures =
        new Throwable[] {
          new HttpConnectTimeoutException("private-url-token"),
          new HttpTimeoutException("private-url-token"),
          new SSLHandshakeException("private-certificate"),
          new UnknownHostException("private-host"),
          new ConnectException("private-address"),
          new TimeoutException("private-body"),
          new java.io.IOException("private-secret")
        };
    var expected =
        new String[] {
          "CONNECT_TIMEOUT",
          "RESPONSE_TIMEOUT",
          "TLS_FAILURE",
          "DNS_FAILURE",
          "CONNECT_FAILURE",
          "RESPONSE_TIMEOUT",
          "CALL_OUTCOME"
        };
    for (int i = 0; i < failures.length; i++) {
      String code = ChatCompletionsModel.transportCode(new ExecutionException(failures[i]));
      assertTrue(code.startsWith("MODEL_" + expected[i]));
      assertTrue(code.endsWith("UNKNOWN"));
      assertFalse(code.contains("private"));
    }
  }
}
