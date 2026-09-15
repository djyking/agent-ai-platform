package io.github.djyking.harness.examples;

/** Alternate entry point: QuestionAnswerExample <local-data-directory>. */
public final class QuestionAnswerExample {
  private QuestionAnswerExample() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Provide one local data directory");
    ExamplesMain.main(new String[] {"qa", args[0]});
  }
}
