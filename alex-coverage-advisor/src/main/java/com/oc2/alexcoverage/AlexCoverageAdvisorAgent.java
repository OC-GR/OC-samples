package com.oc2.alexcoverage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Model-driven coverage explanation advisor; the Host supplies the bounded user message. */
public final class AlexCoverageAdvisorAgent implements Agent {
  private static final String INSTRUCTIONS = """
      You are Alex Coverage Advisor, the personal-insurance products and coverage step of a benefit
      journey. Personal insurance means policies the user buys individually — medical insurance,
      accident insurance, critical-illness insurance, and similar. Explain products, compare options,
      and interpret coverage and claim conditions in general, illustrative terms, based on approved
      product materials. When the needed product information or an authoritative source is missing,
      say clearly that you cannot answer rather than guessing (no-answer). You do not query the
      company's group insurance plan for the employee (that is the employee-benefits step). Never make
      a sales recommendation, underwriting decision, or final claim decision. Reply in the user's
      language, concisely, with plain text and no markdown headers.
      Respond in strict JSON only, with no prose, code fences, or markdown outside the JSON, using
      exactly this shape:
      {"reply": "...", "done": true|false, "summary": {...}}
      - reply: the visible message to the user — the coverage guidance, in the user's language,
        concise, plain text, no markdown headers.
      - done: true only when this coverage step is complete: the question is answered (including a
        clear no-answer when the product information or authoritative source is missing) and you can
        conclude with the summary. Otherwise done=false while you still need information.
      - summary: a JSON object ({} is allowed) with the coverage topic and what was established for
        the next step, e.g. {"topic": "...", "coverage_summary": "...", "open_items": [...]}. Never
        record a sales recommendation, underwriting decision, or final claim decision.
      """;
  private static final String FAILURE = "I couldn't generate coverage guidance just now. Please try again.";
  /** Host Native bridge envelope key; the proposal map allows only "lifecycle" and "summary". */
  static final String TURN_PROPOSAL_METADATA_KEY = "oc2.turn_proposal";
  private static final String PROPOSAL_LIFECYCLE_KEY = "lifecycle";
  private static final String PROPOSAL_SUMMARY_KEY = "summary";
  private static final String COMPLETED = "COMPLETED";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Model model;
  private final AtomicBoolean interrupted = new AtomicBoolean();

  public AlexCoverageAdvisorAgent(Model model) { this.model = model; }

  @Override public String getAgentId() { return "alex-coverage-advisor"; }
  @Override public String getName() { return "Alex Coverage Advisor"; }
  @Override public void interrupt() { interrupted.set(true); }
  @Override public void interrupt(Msg message) { interrupt(); }
  @Override public Mono<Void> observe(Msg message) { return Mono.empty(); }
  @Override public Mono<Void> observe(List<Msg> messages) { return Mono.empty(); }
  @Override public Mono<Msg> call(List<Msg> messages) { return result(messages); }
  @Override public Mono<Msg> call(List<Msg> messages, Class<?> responseFormat) { return call(messages); }
  @Override public Mono<Msg> call(List<Msg> messages, com.fasterxml.jackson.databind.JsonNode schema) { return call(messages); }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options) {
    return result(messages).map(result -> new Event(EventType.AGENT_RESULT, result, true)).flux();
  }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options, Class<?> responseFormat) { return stream(messages, options); }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options, com.fasterxml.jackson.databind.JsonNode outputSchema) { return stream(messages, options); }

  private Mono<Msg> result(List<Msg> messages) {
    if (interrupted.getAndSet(false)) return Mono.just(message("", GenerateReason.INTERRUPTED));
    if (model == null) return Mono.just(message(FAILURE, GenerateReason.MODEL_STOP));
    return generatedText(modelInput(messages))
        .map(AlexCoverageAdvisorAgent::parseOutput)
        .filter(Objects::nonNull)
        .map(AlexCoverageAdvisorAgent::responseMessage)
        .switchIfEmpty(Mono.just(message(FAILURE, GenerateReason.MODEL_STOP)))
        .onErrorResume(error -> Mono.just(message(FAILURE, GenerateReason.MODEL_STOP)));
  }

  private Mono<String> generatedText(List<Msg> input) {
    return model.stream(input, List.of(), null)
        .map(response -> response.getContent() == null ? "" : response.getContent().stream()
            .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText)
            .reduce("", String::concat))
        .reduce("", String::concat);
  }

  private static List<Msg> modelInput(List<Msg> messages) {
    List<Msg> input = new ArrayList<>();
    input.add(Msg.builder().role(MsgRole.SYSTEM).textContent(INSTRUCTIONS).build());
    if (messages != null) input.addAll(messages);
    return List.copyOf(input);
  }

  /** Returns null for blank or malformed model output so the caller emits the safe failure reply. */
  private static Output parseOutput(String text) {
    if (text == null || text.isBlank()) return null;
    try {
      JsonNode root = JSON.readTree(text.trim());
      if (root == null || !root.isObject()) return null;
      JsonNode reply = root.get("reply");
      JsonNode done = root.get("done");
      JsonNode summary = root.get("summary");
      if (reply == null || !reply.isTextual() || reply.asText().isBlank()) return null;
      if (done == null || !done.isBoolean()) return null;
      if (summary == null || !summary.isObject()) return null;
      return new Output(reply.asText().trim(), done.asBoolean(),
          JSON.convertValue(summary, new TypeReference<Map<String, Object>>() {}));
    } catch (JsonProcessingException exception) {
      return null;
    }
  }

  private static Msg responseMessage(Output output) {
    Map<String, Object> proposal = new HashMap<>();
    proposal.put(PROPOSAL_SUMMARY_KEY, output.summary());
    if (output.done()) proposal.put(PROPOSAL_LIFECYCLE_KEY, COMPLETED);
    return Msg.builder().role(MsgRole.ASSISTANT).textContent(output.reply())
        .generateReason(GenerateReason.MODEL_STOP)
        .metadata(Map.of(TURN_PROPOSAL_METADATA_KEY, proposal)).build();
  }

  private static Msg message(String text, GenerateReason reason) {
    return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).generateReason(reason).metadata(Map.of()).build();
  }

  private record Output(String reply, boolean done, Map<String, Object> summary) { }
}