package io.github.djyking.harness.capabilities.rag;

import java.net.URI;
import java.util.*;

/**
 * Retrieval contracts. Scope values must originate from the authenticated host, never model
 * arguments.
 */
public final class Retrieval {
  private Retrieval() {}

  public record Document(String id, String scope, String title, String uri, String text) {
    public Document {
      if (id == null || id.isBlank() || scope == null || scope.isBlank())
        throw new IllegalArgumentException("Document identity and scope are required");
      Objects.requireNonNull(title, "title");
      Objects.requireNonNull(uri, "uri");
      Objects.requireNonNull(text, "text");
      URI.create(uri);
    }
  }

  public record Request(String query, Set<String> authorizedScopes, int maxResults) {
    public Request {
      if (query == null || query.isBlank() || query.length() > 8192)
        throw new IllegalArgumentException("Query must contain 1 to 8192 characters");
      authorizedScopes = Set.copyOf(Objects.requireNonNull(authorizedScopes, "authorizedScopes"));
      if (maxResults < 1 || maxResults > 100)
        throw new IllegalArgumentException("maxResults must be between 1 and 100");
    }
  }

  public record Hit(Document document, double score) {
    public Hit {
      Objects.requireNonNull(document, "document");
      if (!Double.isFinite(score) || score < 0)
        throw new IllegalArgumentException("Invalid retrieval score");
    }
  }

  @FunctionalInterface
  public interface Retriever {
    List<Hit> retrieve(Request request) throws Exception;
  }

  public record Citation(String documentId, String title, String uri) {}

  public record Context(String text, List<Citation> citations, boolean truncated) {
    public Context {
      citations = List.copyOf(citations);
    }
  }
}
