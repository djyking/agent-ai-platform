package io.github.djyking.harness.core;

import io.github.djyking.harness.core.Contracts.ExecutionContext;
import io.github.djyking.harness.core.Contracts.FailureKind;
import io.github.djyking.harness.core.Contracts.InvocationException;
import io.github.djyking.harness.core.Contracts.ModelGateway;
import io.github.djyking.harness.core.Contracts.ModelRequest;
import io.github.djyking.harness.core.Contracts.ModelResponse;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host-composed routing by an explicitly registered provider key, not an online model control
 * plane. The harness freezes profiles in run state; this router forwards the supplied profile
 * unchanged. It does not select another model, retry, or fall back when a provider is missing or
 * fails.
 */
public final class ModelRouter implements ModelGateway {
  private final Map<String, ModelGateway> providers = new ConcurrentHashMap<>();

  /** Provider keys are case-sensitive. Registering an existing key never replaces its gateway. */
  public ModelRouter register(String provider, ModelGateway gateway) {
    if (provider == null || provider.isBlank() || !provider.equals(provider.trim())) {
      throw new IllegalArgumentException(
          "A nonblank provider key without surrounding whitespace is required");
    }
    Objects.requireNonNull(gateway, "gateway");
    if (providers.putIfAbsent(provider, gateway) != null) {
      throw new IllegalArgumentException("Model provider is already registered");
    }
    return this;
  }

  @Override
  public ModelResponse invoke(ModelRequest request, ExecutionContext context) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(context, "context");
    ModelGateway gateway = providers.get(request.profile().provider());
    if (gateway == null) {
      throw new InvocationException(FailureKind.DENIED, "MODEL_PROVIDER_UNREGISTERED");
    }
    return gateway.invoke(request, context);
  }
}
