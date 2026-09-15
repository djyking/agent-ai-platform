package io.github.djyking.harness.capabilities.rag;

import static io.github.djyking.harness.capabilities.rag.Retrieval.*;

import java.util.*;

/**
 * Produces a hard character bound (not a token estimate) and citations only for excerpts actually
 * included.
 */
public final class RetrievalContext {
  private RetrievalContext() {}

  public static Context format(List<Hit> hits, Set<String> authorizedScopes, int maxCharacters) {
    if (maxCharacters < 0) throw new IllegalArgumentException("Negative context capacity");
    Objects.requireNonNull(authorizedScopes, "authorizedScopes");
    List<Citation> citations = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    boolean truncated = false;
    Set<String> seen = new HashSet<>();
    for (Hit hit : hits) {
      Document document = hit.document();
      // Defense in depth for adapters that accidentally return a cross-scope hit.
      if (!authorizedScopes.contains(document.scope()) || !seen.add(document.id())) continue;
      String label = "[" + (citations.size() + 1) + "] ";
      int available = maxCharacters - text.length() - label.length() - 1;
      if (available <= 0 || document.text().isEmpty()) {
        truncated = true;
        continue;
      }
      int count = Math.min(available, document.text().length());
      if (count < document.text().length()
          && count > 0
          && Character.isHighSurrogate(document.text().charAt(count - 1))) count--;
      if (count == 0) {
        truncated = true;
        continue;
      }
      text.append(label).append(document.text(), 0, count).append('\n');
      citations.add(new Citation(document.id(), document.title(), document.uri()));
      truncated |= count < document.text().length();
    }
    return new Context(text.toString(), citations, truncated);
  }
}
