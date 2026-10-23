package io.github.djyking.harness.adapters.telemetry;

import io.github.djyking.harness.adapters.http.HeaderProvider;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Opt-in outgoing trace context. No baggage is propagated by the default W3C-only constructor. */
public final class OpenTelemetryHeaders implements HeaderProvider {
  private final HeaderProvider credentials;
  private final TextMapPropagator propagator;
  private final Context captured;

  public OpenTelemetryHeaders(HeaderProvider credentials) {
    this(credentials, W3CTraceContextPropagator.getInstance(), null);
  }

  public OpenTelemetryHeaders(HeaderProvider credentials, TextMapPropagator propagator) {
    this(credentials, propagator, null);
  }

  private OpenTelemetryHeaders(
      HeaderProvider credentials, TextMapPropagator propagator, Context captured) {
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.propagator = Objects.requireNonNull(propagator, "propagator");
    this.captured = captured;
  }

  @Override
  public Map<String, String> headers(URI endpoint) {
    var headers = new LinkedHashMap<>(credentials.headers(endpoint));
    propagator.inject(captured == null ? Context.current() : captured, headers, Map::put);
    return Map.copyOf(headers);
  }

  @Override
  public HeaderProvider forCurrentCall() {
    return new OpenTelemetryHeaders(credentials.forCurrentCall(), propagator, Context.current());
  }
}
