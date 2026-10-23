package io.github.djyking.harness.examples;

/**
 * Alternate entry point: ApprovalWorkflowExample <local-data-directory>; stops at the first human
 * wait.
 */
public final class ApprovalWorkflowExample {
  private ApprovalWorkflowExample() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Provide one local data directory");
    ExamplesMain.main(new String[] {"approval", args[0]});
  }
}
