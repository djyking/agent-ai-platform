package io.github.djyking.harness.adapters.http;

import java.net.URI;

/** Network endpoint validation, including protection against credentials embedded in URLs. */
public final class HttpEndpoints {
  private HttpEndpoints() {}

  public static URI requireSecureOrLoopback(URI endpoint) {
    if (endpoint == null
        || endpoint.getHost() == null
        || endpoint.getUserInfo() != null
        || endpoint.getFragment() != null
        || endpoint.getQuery() != null) {
      throw new IllegalArgumentException(
          "Endpoint must have a host, without embedded credentials, query or fragment");
    }
    boolean loopback =
        endpoint.getHost().equals("localhost")
            || endpoint.getHost().equals("127.0.0.1")
            || endpoint.getHost().equals("[::1]")
            || endpoint.getHost().equals("::1");
    if (!"https".equalsIgnoreCase(endpoint.getScheme())
        && !("http".equalsIgnoreCase(endpoint.getScheme()) && loopback)) {
      throw new IllegalArgumentException(
          "Use HTTPS for remote endpoints; HTTP is supported only on loopback");
    }
    return endpoint.getRawPath().isEmpty() ? URI.create(endpoint.toString() + "/") : endpoint;
  }
}
