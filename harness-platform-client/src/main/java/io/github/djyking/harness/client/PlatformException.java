package io.github.djyking.harness.client;

/** Contains fixed error codes only; provider bodies and credential-bearing requests are omitted. */
public final class PlatformException extends RuntimeException {
  private final int status;
  private final String code;
  private final String requestId;
  private final boolean outcomeUnknown;

  PlatformException(int status, String code, String requestId, boolean outcomeUnknown) {
    super("Platform request failed: " + code + " (HTTP " + status + ")");
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.outcomeUnknown = outcomeUnknown;
  }

  public int status() { return status; }
  public String code() { return code; }
  public String requestId() { return requestId; }
  public boolean outcomeUnknown() { return outcomeUnknown; }
}
