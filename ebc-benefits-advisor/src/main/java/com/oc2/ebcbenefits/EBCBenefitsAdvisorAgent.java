package com.oc2.ebcbenefits;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Model-driven benefits lookup advisor; the Host supplies the bounded user message. */
public final class EBCBenefitsAdvisorAgent implements Agent {
  private static final String INSTRUCTIONS = """
      You are EBC Benefits Advisor, an employee-benefits lookup advisor. Work with the company group
      insurance plan and the current employee's consented profile. Report the applicable group medical,
      accident, and critical-illness benefits for the lookup. Answer for the current employee using
      only the minimum identity fields needed for the lookup. Report eligibility, coverage scope,
      annual limits, remaining balances, validity periods, and the source of the applicable rules.
      Your output is a benefits lookup result, not a final claim or entitlement decision. If employee
      identity or consent is missing, do not proceed. Reply in the user's language, concisely, with
      plain text and no markdown headers.
      Respond in strict JSON only, with no prose, code fences, or markdown outside the JSON, using
      exactly this shape:
      {"reply": "...", "done": true|false, "summary": {...}}
      - reply: the visible benefits lookup result in the user's language, concise, plain text, with
        no markdown headers.
      - done: true only when eligibility, coverage scope, annual limits, remaining balances, validity
        periods, and rule sources have been reported and you can conclude with the summary. Otherwise
        done=false while identity or consent is missing or more information is needed.
      - summary: a JSON object containing the benefits findings for this interaction. When done=true it
        MUST contain exactly these nine required fields and no other fields:
        {"direct_pay_available": true|false, "direct_pay_scope": "...",
        "reimbursement_required_for": "...", "remaining_balance": 1234.5, "currency": "...",
        "submission_deadline_days": 90, "required_materials": ["..."], "source": "...",
        "boundary": "..."}. direct_pay_available is boolean: whether the service can be direct-paid
        under the applicable plan; direct_pay_scope is text describing what direct pay covers;
        reimbursement_required_for is text describing what must instead be claimed as reimbursement;
        remaining_balance is a number (the remaining annual balance); currency is text (the ISO currency
        code); submission_deadline_days is a number (days within which claims must be submitted);
        required_materials is a JSON array of text items (the materials needed for a claim); source is
        text (the rules source); boundary is text stating what this assistant does not decide (no final
        claim or entitlement decision). When done=false the summary may be {} while identity or consent
        is missing or more information is needed. Never include a final claim or entitlement decision
        in any field.
      """;
  private static final String FAILURE = "I couldn't generate benefits guidance just now. Please try again.";
  /** Host Native bridge envelope key; the proposal map allows only "lifecycle" and "summary". */
  static final String TURN_PROPOSAL_METADATA_KEY = "oc2.turn_proposal";
  private static final String PROPOSAL_LIFECYCLE_KEY = "lifecycle";
  private static final String PROPOSAL_SUMMARY_KEY = "summary";
  private static final String COMPLETED = "COMPLETED";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Model model;
  private final AtomicBoolean interrupted = new AtomicBoolean();

  public EBCBenefitsAdvisorAgent(Model model) { this.model = model; }

  @Override public String getAgentId() { return "ebc-benefits-advisor"; }
  @Override public String getName() { return "EBC Benefits Advisor"; }
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
        .map(EBCBenefitsAdvisorAgent::parseOutput)
        .filter(Objects::nonNull)
        .map(EBCBenefitsAdvisorAgent::responseMessage)
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

  /** Parses structured completion or preserves non-empty model text as an unfinished reply. */
  private static Output parseOutput(String text) {
    if (text == null || text.isBlank()) return null;
    String candidate = text.trim();
    try {
      JsonNode root = JSON.readTree(candidate);
      if (root != null && root.isObject()) {
        return parseStructuredOutput(root);
      }
      return new Output(candidate, false, Map.of());
    } catch (JsonProcessingException ignored) {
      // Preserve ordinary model text below; it cannot complete the step.
      return new Output(candidate, false, Map.of());
    }
  }

  private static Output parseStructuredOutput(JsonNode root) {
    JsonNode reply = root.get("reply");
    JsonNode done = root.get("done");
    JsonNode summary = root.get("summary");
    if (reply == null || !reply.isTextual() || reply.asText().isBlank()
        || done == null || !done.isBoolean() || summary == null || !summary.isObject()
        || !contractSummary(summary, done.asBoolean())) return null;
    return new Output(reply.asText().trim(), done.asBoolean(),
        JSON.convertValue(summary, new TypeReference<Map<String, Object>>() {}));
  }

  private static Msg responseMessage(Output output) {
    Map<String, Object> proposal = new HashMap<>();
    if (!output.summary().isEmpty()) proposal.put(PROPOSAL_SUMMARY_KEY, output.summary());
    if (output.done()) proposal.put(PROPOSAL_LIFECYCLE_KEY, COMPLETED);
    if (proposal.isEmpty()) {
      // done=false with an empty summary: keep the step open with the visible reply only; an empty
      // summary must never be presented to the Host bridge as a completion proposal.
      return Msg.builder().role(MsgRole.ASSISTANT).textContent(output.reply())
          .generateReason(GenerateReason.MODEL_STOP).metadata(Map.of()).build();
    }
    return Msg.builder().role(MsgRole.ASSISTANT).textContent(output.reply())
        .generateReason(GenerateReason.MODEL_STOP)
        .metadata(Map.of(TURN_PROPOSAL_METADATA_KEY, proposal)).build();
  }

  /**
   * Host-registered recipient contract for this step's typed summary: exactly direct_pay_available
   * (BOOLEAN), direct_pay_scope (TEXT), reimbursement_required_for (TEXT), remaining_balance
   * (NUMBER), currency (TEXT), submission_deadline_days (NUMBER), required_materials (JSON array),
   * source (TEXT), boundary (TEXT). done=true requires all nine fields with contract types and no
   * extra fields; done=false allows {} or any subset of the contract fields with contract types.
   */
  private static boolean contractSummary(JsonNode summary, boolean done) {
    ContractField[] fields = {
        new ContractField("direct_pay_available", JsonNode::isBoolean),
        new ContractField("direct_pay_scope", JsonNode::isTextual),
        new ContractField("reimbursement_required_for", JsonNode::isTextual),
        new ContractField("remaining_balance", JsonNode::isNumber),
        new ContractField("currency", JsonNode::isTextual),
        new ContractField("submission_deadline_days", JsonNode::isNumber),
        new ContractField("required_materials", JsonNode::isArray),
        new ContractField("source", JsonNode::isTextual),
        new ContractField("boundary", JsonNode::isTextual)};
    if (done) {
      if (summary.size() != fields.length) return false;
    } else if (summary.isEmpty()) {
      return true;
    }
    Map<String, Predicate<JsonNode>> byName = new HashMap<>();
    for (ContractField field : fields) byName.put(field.name(), field.type());
    for (Iterator<Map.Entry<String, JsonNode>> it = summary.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = it.next();
      Predicate<JsonNode> type = byName.get(entry.getKey());
      JsonNode value = entry.getValue();
      if (type == null || value == null || value.isNull() || !type.test(value)) return false;
    }
    return true;
  }

  private record ContractField(String name, Predicate<JsonNode> type) { }

  private static Msg message(String text, GenerateReason reason) {
    return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).generateReason(reason).metadata(Map.of()).build();
  }

  private record Output(String reply, boolean done, Map<String, Object> summary) { }
}