package io.github.djyking.harness.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Contracts.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelRouterTest {
  @Test
  void routesByProviderAndPreservesTheFrozenRequestAndExecutionContext() {
    var firstCalls = new AtomicInteger();
    var secondCalls = new AtomicInteger();
    var request = request("second");
    var context = context();
    var expected = response("second provider");
    var router =
        new ModelRouter()
            .register(
                "first",
                (input, execution) -> {
                  firstCalls.incrementAndGet();
                  return response("first provider");
                })
            .register(
                "second",
                (input, execution) -> {
                  secondCalls.incrementAndGet();
                  assertSame(request, input);
                  assertSame(context, execution);
                  assertEquals("frozen-model-version", input.profile().model());
                  return expected;
                });
    assertSame(expected, router.invoke(request, context));
    assertEquals(0, firstCalls.get());
    assertEquals(1, secondCalls.get());
  }

  @Test
  void duplicateRegistrationCannotSilentlyReplaceAProvider() {
    var router = new ModelRouter().register("provider", (request, context) -> response("original"));
    assertThrows(
        IllegalArgumentException.class,
        () -> router.register("provider", (request, context) -> response("replacement")));
    assertEquals("original", router.invoke(request("provider"), context()).message().content());
  }

  @Test
  void missingProviderIsDeniedWithoutCallingAnAvailableProvider() {
    var calls = new AtomicInteger();
    var router =
        new ModelRouter()
            .register(
                "available",
                (request, context) -> {
                  calls.incrementAndGet();
                  return response("unexpected fallback");
                });
    var error =
        assertThrows(InvocationException.class, () -> router.invoke(request("missing"), context()));
    assertEquals(FailureKind.DENIED, error.kind());
    assertEquals("MODEL_PROVIDER_UNREGISTERED", error.getMessage());
    assertEquals(0, calls.get());
  }

  @Test
  void providerFailurePropagatesWithoutRetryOrFallback() {
    var primaryCalls = new AtomicInteger();
    var fallbackCalls = new AtomicInteger();
    var failure = new InvocationException(FailureKind.UNKNOWN, "MODEL_CALL_OUTCOME_UNKNOWN");
    var router =
        new ModelRouter()
            .register(
                "primary",
                (request, context) -> {
                  primaryCalls.incrementAndGet();
                  throw failure;
                })
            .register(
                "fallback",
                (request, context) -> {
                  fallbackCalls.incrementAndGet();
                  return response("unexpected fallback");
                });
    assertSame(
        failure,
        assertThrows(
            InvocationException.class, () -> router.invoke(request("primary"), context())));
    assertEquals(1, primaryCalls.get());
    assertEquals(0, fallbackCalls.get());
  }

  private ModelRequest request(String provider) {
    var profile =
        new ModelProfile(
            "profile-version-1",
            provider,
            "frozen-model-version",
            4096,
            256,
            1000,
            Json.object().put("temperature", 0));
    return new ModelRequest(
        "run:invocation", profile, List.of(Message.text("user", "test")), List.of());
  }

  private ExecutionContext context() {
    return new ExecutionContext(
        "run",
        "node",
        "run:invocation",
        "run:invocation:attempt:1",
        new Actor("test-user", "test-project", Set.of()),
        Instant.now().plusSeconds(10),
        "1234567890abcdef1234567890abcdef");
  }

  private ModelResponse response(String text) {
    return new ModelResponse(
        Message.text("assistant", text), FinishReason.FINAL, new Usage(1, 1, true));
  }
}
