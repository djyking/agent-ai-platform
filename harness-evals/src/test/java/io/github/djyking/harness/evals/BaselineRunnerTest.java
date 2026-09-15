package io.github.djyking.harness.evals;

import static org.junit.jupiter.api.Assertions.*;

import io.github.djyking.harness.core.Json;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BaselineRunnerTest {
  @TempDir Path directory;

  @Test
  void realRuntimeAndRetrievalSatisfyTheVersionedEngineeringBaseline() throws Exception {
    var dataset = BaselineRunner.builtin();
    var report = new BaselineRunner().run(dataset, directory);
    assertTrue(report.passed(), () -> Json.write(report));
    assertEquals(9, report.totalCases());
    assertEquals(9, report.passedCases());
    assertEquals(Json.hash(dataset), report.datasetFingerprint());
    assertTrue(report.cases().stream().allMatch(c -> !c.checks().isEmpty()));
    assertTrue(report.limitations().stream().anyMatch(l -> l.contains("not real model")));
  }

  @Test
  void wrongExpectationsAndMissingObservationNamesFailInsteadOfBeingIgnored() throws Exception {
    var dataset = BaselineRunner.builtin();
    var source = dataset.scenarios().get(0);
    var bad =
        new BaselineRunner.Scenario(
            "bad-expectations",
            source.kind(),
            source.project(),
            source.question(),
            source.toolName(),
            source.arguments(),
            null,
            false,
            Json.read("{\"citationIds\":[\"finance-private\"],\"unrecognizedMetric\":0}"));
    var report =
        new BaselineRunner()
            .run(
                new BaselineRunner.Dataset(
                    1, "negative-test", "SYNTHETIC", dataset.documents(), List.of(bad)),
                directory);
    assertFalse(report.passed());
    assertEquals(0, report.passedCases());
    assertNull(report.cases().get(0).failureCode());
    assertTrue(report.cases().get(0).checks().stream().noneMatch(BaselineRunner.Check::passed));
  }

  @Test
  void cliWritesReportBeforeFailingARegression() throws Exception {
    var dataset = BaselineRunner.builtin();
    var source = dataset.scenarios().get(1);
    var bad =
        new BaselineRunner.Scenario(
            "fail-cli",
            source.kind(),
            source.project(),
            source.question(),
            source.toolName(),
            source.arguments(),
            null,
            false,
            Json.read("{\"status\":\"FAILED\"}"));
    Path input = directory.resolve("input.json"), output = directory.resolve("report.json");
    Files.writeString(
        input,
        Json.write(
            new BaselineRunner.Dataset(
                1, "negative-cli", "SYNTHETIC", dataset.documents(), List.of(bad))));
    assertThrows(
        IllegalStateException.class,
        () ->
            EvalsMain.main(
                new String[] {
                  input.toString(), output.toString(), directory.resolve("sql").toString()
                }));
    var report = Json.read(Files.readString(output));
    assertFalse(report.path("passed").asBoolean());
    assertEquals(1, report.path("totalCases").asInt());
  }
}
