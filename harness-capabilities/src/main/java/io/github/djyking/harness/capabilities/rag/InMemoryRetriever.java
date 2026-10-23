package io.github.djyking.harness.capabilities.rag;

import static io.github.djyking.harness.capabilities.rag.Retrieval.*;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A deterministic lexical baseline for small corpora and offline tests; no embedding service is
 * required.
 */
public final class InMemoryRetriever implements Retriever {
  private static final Pattern SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");
  private final List<Document> documents;

  public InMemoryRetriever(List<Document> documents) {
    this.documents = List.copyOf(documents);
    Set<String> identities = new HashSet<>();
    for (Document document : documents)
      if (!identities.add(document.id()))
        throw new IllegalArgumentException("Duplicate document id: " + document.id());
  }

  @Override
  public List<Hit> retrieve(Request request) {
    Set<String> query = tokens(request.query());
    if (query.isEmpty() || request.authorizedScopes().isEmpty()) return List.of();
    return documents.stream()
        .filter(document -> request.authorizedScopes().contains(document.scope()))
        .map(
            document ->
                new Hit(document, score(query, tokens(document.title() + " " + document.text()))))
        .filter(hit -> hit.score() > 0)
        .sorted(
            Comparator.comparingDouble(Hit::score)
                .reversed()
                .thenComparing(hit -> hit.document().id()))
        .limit(request.maxResults())
        .toList();
  }

  private static Set<String> tokens(String text) {
    return Arrays.stream(SEPARATOR.split(text.toLowerCase(Locale.ROOT)))
        .filter(token -> !token.isBlank())
        .collect(Collectors.toSet());
  }

  private static double score(Set<String> query, Set<String> document) {
    return (double) query.stream().filter(document::contains).count() / query.size();
  }
}
