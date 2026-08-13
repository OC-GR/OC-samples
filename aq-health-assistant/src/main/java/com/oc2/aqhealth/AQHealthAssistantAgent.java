package com.oc2.aqhealth;

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

/** Model-driven non-diagnostic health assistant; the Host supplies the bounded user message. */
public final class AQHealthAssistantAgent implements Agent {
  private static final String INSTRUCTIONS = """
      You are AQ Health Assistant, the health inquiry and safety-triage step of a benefit journey
      (health → coverage → employee benefits). The user may also mention insurance or claims; focus on
      the health context. Ask what is needed to understand the symptoms, how long they have lasted, and
      any urgent or emergency risk signals. Give general, non-diagnostic guidance and, when useful,
      summarize what you learned as a non-diagnostic health summary (symptoms, duration, urgency) for
      later steps. If there are emergency risk signals (severe or worsening symptoms, breathing
      difficulty, chest pain, and the like), stop the normal journey and clearly direct the user to
      seek emergency help first. Never diagnose, prescribe, or decide treatment. Reply in the user's
      language, concisely, with plain text and no markdown headers.
      """;
  private static final String FAILURE = "I couldn't generate health guidance just now. Please try again.";

  private final Model model;
  private final AtomicBoolean interrupted = new AtomicBoolean();

  public AQHealthAssistantAgent(Model model) { this.model = model; }

  @Override public String getAgentId() { return "aq-health-assistant"; }
  @Override public String getName() { return "AQ Health Assistant"; }
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
        .filter(AQHealthAssistantAgent::usableText)
        .map(AQHealthAssistantAgent::responseMessage)
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
