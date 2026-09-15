package io.github.djyking.harness.evals;

import io.github.djyking.harness.core.Json;
import java.nio.file.*;

/** Writes a machine-readable report and fails the process if any expected invariant differs. */
public final class EvalsMain {
  private EvalsMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3)
      throw new IllegalArgumentException(
          "Usage: builtin|<dataset.json> <report.json> [data-directory]");
    BaselineRunner.Dataset dataset =
        "builtin".equals(args[0])
            ? BaselineRunner.builtin()
            : BaselineRunner.load(Path.of(args[0]));
    Path reportFile = Path.of(args[1]).toAbsolutePath();
    Path data = args.length == 3 ? Path.of(args[2]) : reportFile.getParent().resolve("eval-data");
    BaselineRunner.Report report = new BaselineRunner().run(dataset, data);
    Files.createDirectories(reportFile.getParent());
    Files.writeString(
        reportFile, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    System.out.println(
        Json.write(
            Json.object()
                .put("dataset", report.datasetId())
                .put("passed", report.passed())
                .put("passedCases", report.passedCases())
                .put("totalCases", report.totalCases())
                .put("report", reportFile.toString())));
    if (!report.passed())
      throw new IllegalStateException("Engineering baseline expectations failed; inspect report");
  }
}
