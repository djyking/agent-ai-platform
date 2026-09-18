package io.github.djyking.harness.platform;

/** Public failures contain fixed codes only, never submitted values or provider messages. */
public final class ApiFailure extends RuntimeException {
  public final int status;
  public final String code;

  public ApiFailure(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }

  public static ApiFailure invalid() {
    return new ApiFailure(400, "INVALID_REQUEST");
  }

  public static ApiFailure denied() {
    return new ApiFailure(403, "FORBIDDEN");
  }

  public static ApiFailure hidden() {
    return new ApiFailure(404, "NOT_FOUND");
  }
}
