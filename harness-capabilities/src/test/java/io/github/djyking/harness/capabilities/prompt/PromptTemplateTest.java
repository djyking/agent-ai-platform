package io.github.djyking.harness.capabilities.prompt;

import static io.github.djyking.harness.capabilities.prompt.PromptTemplate.VariableType.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptTemplateTest {
  @TempDir Path directory;

  @Test
  void rejectsUndeclaredAndWronglyTypedVariables() {
    var prompt =
        new PromptTemplate(
            "answer",
            "1",
            "Answer clearly",
            "{{question}} limit={{limit}}",
            Map.of("question", STRING, "limit", NUMBER));
    assertThrows(
        IllegalArgumentException.class,
        () -> prompt.render(Map.of("question", "What?", "limit", "10")));
    assertThrows(
        IllegalArgumentException.class,
        () -> prompt.render(Map.of("question", "What?", "limit", 10, "secret", "x")));
    assertThrows(
        IllegalArgumentException.class,
        () -> prompt.render(Map.of("question", "What?", "limit", Double.NaN)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PromptTemplate("answer", "1", "", "{{missing}}", Map.of()));
  }

  @Test
  void inputIsLiteralAndSnapshotRemainsStableAcrossLaterTemplateVersions() {
    var v1 =
        new PromptTemplate("answer", "1", "Policy v1", "{{question}}", Map.of("question", STRING));
    var rendered = v1.render(Map.of("question", "{{newInstruction}} $1 \\ test"));
    var v2 =
        new PromptTemplate("answer", "2", "Policy v2", "{{question}}", Map.of("question", STRING));
    assertEquals("{{newInstruction}} $1 \\ test", rendered.user());
    assertEquals("Policy v1", rendered.system());
    assertNotEquals(rendered.fingerprint(), v2.render(Map.of("question", "hello")).fingerprint());
    assertEquals(
        rendered.fingerprint(), v1.render(Map.of("question", "changed input")).fingerprint());
    assertTrue(rendered.effectiveInputsJson().contains("newInstruction"));
  }

  @Test
  void fileVersionsCannotBeOverwrittenAndTraversalIsRejected() throws Exception {
    var repository = new FilePromptRepository(directory);
    var prompt = new PromptTemplate("answer", "1.0", "", "Hello {{name}}", Map.of("name", STRING));
    repository.publish(prompt);
    assertEquals(prompt, repository.load("answer", "1.0"));
    assertThrows(FileAlreadyExistsException.class, () -> repository.publish(prompt));
    assertThrows(IllegalArgumentException.class, () -> repository.load("../secret", "1"));
    Files.writeString(
        directory.resolve("answer/2.json"), Files.readString(directory.resolve("answer/1.0.json")));
    assertThrows(java.io.IOException.class, () -> repository.load("answer", "2"));
  }
}
