package io.github.djyking.harness.capabilities.prompt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;

/**
 * Immutable version files. A host may replace this repository with its own configuration service.
 */
public final class FilePromptRepository {
  private final Path root;
  private final ObjectMapper json =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  public FilePromptRepository(Path root) throws IOException {
    Files.createDirectories(root);
    this.root = root.toRealPath();
  }

  public PromptTemplate load(String id, String version) throws IOException {
    Path file = path(id, version).toRealPath();
    if (!file.startsWith(root)) throw new IOException("Prompt path escapes repository");
    PromptTemplate template = json.readValue(Files.readString(file), PromptTemplate.class);
    if (!id.equals(template.id()) || !version.equals(template.version()))
      throw new IOException("Prompt identity does not match its file path");
    return template;
  }

  public void publish(PromptTemplate template) throws IOException {
    Path file = path(template.id(), template.version());
    Files.createDirectories(file.getParent());
    if (!file.getParent().toRealPath().startsWith(root))
      throw new IOException("Prompt path escapes repository");
    Files.writeString(
        file,
        json.writerWithDefaultPrettyPrinter().writeValueAsString(template),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
  }

  private Path path(String id, String version) {
    if (id == null
        || !id.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")
        || version == null
        || !version.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}"))
      throw new IllegalArgumentException("Invalid prompt identity");
    return root.resolve(id).resolve(version + ".json");
  }
}
