package com.oc2.alexcoverage;

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
import java.util.List;
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
      """;
  private static final String FAILURE = "I couldn't generate coverage guidance just now. Please try again.";

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
        .filter(AlexCoverageAdvisorAgent::usableText)
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

  private static boolean usableText(String value) {
    return value != null && !value.isBlank();
  }

  private static Msg responseMessage(String text) {
    return message(text.trim(), GenerateReason.MODEL_STOP);
  }

  private static Msg message(String text, GenerateReason reason) {
    return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).generateReason(reason).build();
  }
}
