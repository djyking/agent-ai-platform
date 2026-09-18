package io.github.djyking.harness.platform;

import io.github.djyking.harness.core.Contracts.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Run-specific execution identity lets async call threads recheck the exact delegated authority.
 */
public final class RuntimeAccess implements AccessPolicy {
  private record Frame(String project, String subject, Set<String> allowed) {}

  private final ThreadLocal<Frame> request = new ThreadLocal<>();
  private final PlatformRepository repository;
  private final IdentityProvider identities;
  private java.util.function.BiConsumer<String, String> releaseCheck = (project, release) -> {};
  private java.util.function.BiConsumer<PlatformRepository.Owned, IdentityProvider.Principal>
      principalCheck = (owned, principal) -> {};

  public void principalCheck(
      java.util.function.BiConsumer<PlatformRepository.Owned, IdentityProvider.Principal> check) {
    this.principalCheck = Objects.requireNonNull(check);
  }

  public void releaseCheck(java.util.function.BiConsumer<String, String> check) {
    this.releaseCheck = Objects.requireNonNull(check);
  }

  public RuntimeAccess(PlatformRepository repository, IdentityProvider identities) {
    this.repository = repository;
    this.identities = identities;
  }

  public <T> T command(Actor actor, Set<String> allowed, Supplier<T> operation) {
    Frame previous = request.get();
    request.set(new Frame(actor.project(), actor.subject(), Set.copyOf(allowed)));
    try {
      return operation.get();
    } finally {
      if (previous == null) request.remove();
      else request.set(previous);
    }
  }

  @Override
  public void check(Actor actor, String permission, String resource) {
    Frame local = request.get();
    if (local != null
        && local.project.equals(actor.project())
        && local.subject.equals(actor.subject())) {
      if (local.allowed.contains(permission) && actor.permissions().contains(permission)) return;
      throw new InvocationException(FailureKind.DENIED, "PLATFORM_PERMISSION_DENIED");
    }
    try {
      if (!actor.subject().startsWith("run:")) throw ApiFailure.denied();
      var owned = repository.owned(actor.subject().substring(4));
      releaseCheck.accept(owned.project(), owned.release().releaseId());
      if (!owned.project().equals(actor.project())
          || !actor.permissions().contains(permission)
          || !owned.release().executionPermissions().contains(permission))
        throw ApiFailure.denied();
      var current =
          identities.current(
              owned.application(), owned.project(), owned.delegation(), owned.runId());
      if (!current.application().equals(owned.application())
          || !current.project().equals(owned.project())
          || !current.subject().equals(owned.subject())
          || !current.permits(permission)) throw ApiFailure.denied();
      principalCheck.accept(owned, current);
    } catch (RuntimeException ex) {
      throw new InvocationException(FailureKind.DENIED, "PLATFORM_CURRENT_ACCESS_DENIED");
    }
  }
}
