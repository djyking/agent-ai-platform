package io.github.djyking.harness.adapters.http;

import java.net.URI;
import java.util.Map;

/** Supplies current credentials at request time. Never serialize the provider into run state. */
@FunctionalInterface
public interface HeaderProvider {
  Map<String, String> headers(URI endpoint);

  /** Capture call-scoped metadata before an asynchronous transport changes threads. */
  default HeaderProvider forCurrentCall() {
    return this;
  }

  static HeaderProvider none() {
    return endpoint -> Map.of();
  }
}
