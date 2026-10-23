package io.github.djyking.harness.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.djyking.harness.capabilities.rag.Retrieval;
import io.github.djyking.harness.core.Json;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

/** Frozen synthetic gold and transparent rule scoring, separate from engineering regression. */
public final class QualityBaseline {
  private QualityBaseline() {}

  public record Fact(String id, List<String> patterns) {}
  public record Case(String id, String scope, String question, boolean expectedAnswerable,
      List<String> expectedDocumentIds, List<Fact> requiredFacts, List<String> forbiddenAnswerPatterns) {}
  public record Dataset(String id, String version, String classification, String language, String promptVersion,
      String systemPrompt, Map<String, Double> thresholds, Map<String, String> protectedMarkers,
      List<Retrieval.Document> documents, List<Case> cases) {}

  public static Dataset builtin() throws Exception {
    try (InputStream input = QualityBaseline.class.getResourceAsStream("/datasets/quality-synthetic-v1.json")) {
      if (input == null) throw new IllegalStateException("Quality dataset missing");
      Dataset dataset = Json.MAPPER.readValue(input, Dataset.class);
      validate(dataset);
      return dataset;
    }
  }

  public static void validate(Dataset dataset) {
    if (!"SYNTHETIC".equals(dataset.classification()) || dataset.cases().size() != 10
        || dataset.documents().size() > 100 || !"1".equals(dataset.version()))
      throw new IllegalArgumentException("Unsupported frozen synthetic quality dataset");
    Map<String, Retrieval.Document> documents = new HashMap<>();
    for (var document : dataset.documents()) {
      if (documents.put(document.id(), document) != null || !document.uri().startsWith("https://synthetic.invalid/"))
        throw new IllegalArgumentException("Duplicate or non-synthetic quality document");
    }
    Set<String> ids = new HashSet<>();
    for (Case c : dataset.cases()) {
      if (!ids.add(c.id()) || c.expectedAnswerable() != !c.expectedDocumentIds().isEmpty()
          || c.expectedAnswerable() != !c.requiredFacts().isEmpty())
        throw new IllegalArgumentException("Invalid quality gold case");
      for (String id : c.expectedDocumentIds()) {
        if (!documents.containsKey(id) || !documents.get(id).scope().equals(c.scope()))
          throw new IllegalArgumentException("Gold document outside case authorization");
      }
      for (Fact fact : c.requiredFacts()) {
        if (fact.patterns().isEmpty()) throw new IllegalArgumentException("Empty fact rule");
        fact.patterns().forEach(Pattern::compile);
      }
      c.forbiddenAnswerPatterns().forEach(Pattern::compile);
    }
    if (!documents.keySet().containsAll(dataset.protectedMarkers().keySet()))
      throw new IllegalArgumentException("Unknown protected-marker document");
    Map<String, Double> frozenThresholds = Map.of("retrievalRecall", 1d, "unauthorizedRetrievalCount", 0d,
        "protectedMarkerLeakCount", 0d, "citationValidityRate", 1d, "mandatoryFactCaseRate", 1d,
        "answerabilityAccuracy", 1d, "allCasePassRate", 1d);
    if (!frozenThresholds.equals(dataset.thresholds())) throw new IllegalArgumentException("Frozen quality thresholds changed");
  }

  public static ObjectNode score(Dataset dataset, Case c, JsonNode context, String rawAnswer) {
    Map<String, Retrieval.Document> docs = new HashMap<>();
    dataset.documents().forEach(d -> docs.put(d.id(), d));
    Set<String> retrieved = new LinkedHashSet<>();
    context.path("citations").forEach(citation -> retrieved.add(citation.path("documentId").asText()));
    long unauthorized = retrieved.stream().filter(id -> !docs.containsKey(id) || !docs.get(id).scope().equals(c.scope())).count();
    long hits = c.expectedDocumentIds().stream().filter(retrieved::contains).count();
    int leaks = 0;
    for (var entry : dataset.protectedMarkers().entrySet()) {
      if (!docs.get(entry.getKey()).scope().equals(c.scope()) && rawAnswer.contains(entry.getValue())) leaks++;
    }
    JsonNode answer;
    try { answer = Json.read(rawAnswer); } catch (RuntimeException invalid) { answer = Json.object(); }
    boolean format = answer.isObject() && answer.size() == 3 && answer.path("answerable").isBoolean()
        && answer.path("answer").isTextual() && answer.path("citations").isArray();
    String text = answer.path("answer").asText("");
    Set<String> cited = new LinkedHashSet<>();
    boolean citationTypes = true;
    for (JsonNode citation : answer.path("citations")) {
      if (!citation.isTextual() || !cited.add(citation.asText())) citationTypes = false;
    }
    boolean citations = format && citationTypes && retrieved.containsAll(cited)
        && cited.stream().allMatch(id -> docs.containsKey(id) && docs.get(id).scope().equals(c.scope()))
        && (c.expectedAnswerable() ? cited.containsAll(c.expectedDocumentIds()) : cited.isEmpty());
    boolean answerability = format && answer.path("answerable").asBoolean() == c.expectedAnswerable()
        && (c.expectedAnswerable() || Pattern.compile("insufficient evidence", Pattern.CASE_INSENSITIVE).matcher(text).find());
    ObjectNode result = Json.object().put("caseId", c.id()).put("scope", c.scope()).put("question", c.question())
        .put("expectedAnswerable", c.expectedAnswerable()).put("responseFormatValid", format)
        .put("unauthorizedRetrievalCount", unauthorized).put("protectedMarkerLeakCount", leaks)
        .put("retrievalGoldHits", hits).put("retrievalGoldCount", c.expectedDocumentIds().size())
        .put("citationValid", citations).put("answerabilityCorrect", answerability);
    result.set("retrievedDocumentIds", Json.tree(retrieved));
    result.set("expectedDocumentIds", Json.tree(c.expectedDocumentIds()));
    result.set("citedDocumentIds", Json.tree(cited));
    var facts = result.putArray("factChecks");
    boolean factsPass = true;
    for (Fact fact : c.requiredFacts()) {
      boolean hit = fact.patterns().stream().anyMatch(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find());
      facts.add(Json.object().put("id", fact.id()).put("passed", hit));
      factsPass &= hit;
    }
    int forbidden = (int)c.forbiddenAnswerPatterns().stream().filter(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(rawAnswer).find()).count();
    result.put("mandatoryFactsPassed", factsPass).put("forbiddenAnswerMatches", forbidden)
        .put("passed", format && unauthorized == 0 && leaks == 0 && hits == c.expectedDocumentIds().size()
            && citations && answerability && factsPass && forbidden == 0);
    result.set("syntheticContext", context);
    result.put("syntheticModelResponse", rawAnswer);
    return result;
  }

  public static ObjectNode aggregate(Dataset dataset, List<ObjectNode> cases) {
    long gold = 0, hits = 0, unauthorized = 0, leaks = 0, cited = 0, facts = 0, answered = 0, passed = 0, answerable = 0;
    for (ObjectNode c : cases) {
      gold += c.path("retrievalGoldCount").asLong(); hits += c.path("retrievalGoldHits").asLong();
      unauthorized += c.path("unauthorizedRetrievalCount").asLong(); leaks += c.path("protectedMarkerLeakCount").asLong();
      if (c.path("citationValid").asBoolean()) cited++;
      if (c.path("expectedAnswerable").asBoolean()) { answerable++; if (c.path("mandatoryFactsPassed").asBoolean()) facts++; }
      if (c.path("answerabilityCorrect").asBoolean()) answered++;
      if (c.path("passed").asBoolean()) passed++;
    }
    int count = cases.size();
    ObjectNode result = Json.object().put("completedCases", count).put("passedCases", passed)
        .put("retrievalRecall", gold == 0 ? 0 : (double)hits/gold).put("retrievalGoldHits", hits).put("retrievalGoldCount", gold)
        .put("unauthorizedRetrievalCount", unauthorized).put("protectedMarkerLeakCount", leaks)
        .put("citationValidityRate", count == 0 ? 0 : (double)cited/count)
        .put("mandatoryFactCaseRate", answerable == 0 ? 0 : (double)facts/answerable)
        .put("answerabilityAccuracy", count == 0 ? 0 : (double)answered/count)
        .put("allCasePassRate", count == 0 ? 0 : (double)passed/count);
    boolean thresholdsMet = count == dataset.cases().size();
    for (var entry : dataset.thresholds().entrySet()) thresholdsMet &= result.path(entry.getKey()).asDouble() == entry.getValue();
    return result.put("thresholdsMet", thresholdsMet);
  }
}
