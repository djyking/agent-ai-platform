package io.github.djyking.harness.capabilities.rag;

import static io.github.djyking.harness.capabilities.rag.Retrieval.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class RetrievalTest {
  private final Document publicDocument =
      new Document(
          "public", "team-a", "Public guide", "memory:/public", "Deploy the service safely.");
  private final Document secretDocument =
      new Document(
          "secret", "team-b", "Private", "memory:/secret", "Deploy privileged secret keys.");

  @Test
  void authorizationFiltersCandidatesAndContextRejectsLeakedHits() throws Exception {
    var retriever = new InMemoryRetriever(List.of(secretDocument, publicDocument));
    var hits = retriever.retrieve(new Request("deploy", Set.of("team-a"), 10));
    assertEquals(List.of("public"), hits.stream().map(hit -> hit.document().id()).toList());
    assertTrue(retriever.retrieve(new Request("deploy", Set.of(), 10)).isEmpty());
    var context =
        RetrievalContext.format(
            List.of(new Hit(secretDocument, 1), new Hit(publicDocument, 1)),
            Set.of("team-a"),
            1000);
    assertFalse(context.text().contains("secret"));
    assertEquals(
        List.of(new Citation("public", "Public guide", "memory:/public")), context.citations());
  }

  @Test
  void contextHasHardBoundAndCitationsOnlyForIncludedExcerpts() {
    List<Hit> hits = List.of(new Hit(publicDocument, 1), new Hit(secretDocument, 1));
    for (int limit = 0; limit < 70; limit++) {
      var context = RetrievalContext.format(hits, Set.of("team-a", "team-b"), limit);
      assertTrue(context.text().length() <= limit);
      for (int i = 0; i < context.citations().size(); i++)
        assertTrue(context.text().contains("[" + (i + 1) + "] "));
    }
    assertTrue(RetrievalContext.format(hits, Set.of("team-a"), 10).truncated());
    assertTrue(RetrievalContext.format(hits, Set.of("team-a"), 0).citations().isEmpty());
  }

  @Test
  void rankingIsDeterministicAndResultCountIsBounded() {
    var retriever = new InMemoryRetriever(List.of(secretDocument, publicDocument));
    assertEquals(
        "public",
        retriever
            .retrieve(new Request("deploy", Set.of("team-a", "team-b"), 1))
            .get(0)
            .document()
            .id());
    assertThrows(
        IllegalArgumentException.class, () -> new Request("deploy", Set.of("team-a"), 101));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InMemoryRetriever(List.of(publicDocument, publicDocument)));
  }
}
