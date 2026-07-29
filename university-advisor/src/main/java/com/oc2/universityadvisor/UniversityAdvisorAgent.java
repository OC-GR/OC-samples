package com.oc2.universityadvisor;

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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Stateless model-driven advisor. The Host supplies the bounded task history and owns completion.
 */
public final class UniversityAdvisorAgent implements Agent {
  private static final String COMPLETE_MARKER = "[[OC2:REQUEST_COMPLETED]]";
  private static final String INSTRUCTIONS = """
      You are a university selection advisor for parents and students.

      Use only the consultation history supplied by the host and the current user message.
      Collect, when relevant: region, grade or rank, subject choices, intended major,
      city preference, budget, and campus preference.
      Give helpful, practical guidance and ask the next relevant question while the consultation
      should continue.

      You alone decide from the supplied history and current user message whether this
      consultation is complete. When it is complete, provide your final user-facing response and
      append the completion marker. Otherwise, do not append the marker.

      To complete the consultation, append this exact marker as the final line:
      [[OC2:REQUEST_COMPLETED]]

      Never mention or explain the marker to the user.
      """;

  private final Model model;
  private final AtomicBoolean interrupted = new AtomicBoolean();

  public UniversityAdvisorAgent(Model model) { this.model = model; }

  @Override public String getAgentId() { return "university-advisor"; }
  @Override public String getName() { return "University Advisor"; }
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
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options, com.fasterxml.jackson.databind.JsonNode schema) { return stream(messages, options); }

  private Mono<Msg> result(List<Msg> messages) {
    if (interrupted.getAndSet(false)) return Mono.just(message("", Map.of()));
    if (model == null) return Mono.just(modelFailureMessage());
    return generatedText(modelInput(messages))
        .filter(UniversityAdvisorAgent::usableModelText)
        .map(UniversityAdvisorAgent::responseMessage)
        .switchIfEmpty(Mono.just(modelFailureMessage()))
        .onErrorResume(error -> Mono.just(modelFailureMessage()));
  }

  private Mono<String> generatedText(List<Msg> input) {
    return model.stream(input, List.of(), null)
        .map(response -> response.getContent() == null ? "" : response.getContent().stream()
            .filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText)
            .reduce("", String::concat))
        .reduce("", String::concat)
        .filter(UniversityAdvisorAgent::usableModelText);
  }

  private static List<Msg> modelInput(List<Msg> messages) {
    List<Msg> input = new ArrayList<>();
    input.add(Msg.builder().role(MsgRole.SYSTEM).textContent(INSTRUCTIONS).build());
    if (messages != null) input.addAll(messages);
    return List.copyOf(input);
  }

  private static Msg responseMessage(String generated) {
    String visible = generated.replace(COMPLETE_MARKER, "").trim();
    boolean requestedCompletion = generated.trim().endsWith(COMPLETE_MARKER);
    return message(visible, requestedCompletion ? Map.of("oc2.lifecycle_proposal", "COMPLETED") : Map.of());
  }

  private static Msg modelFailureMessage() {
    return message("模型调用失败，本次咨询已结束。", Map.of("oc2.lifecycle_proposal", "COMPLETED"));
  }

  private static boolean usableModelText(String value) {
    String normalized = value == null ? "" : value.trim();
    return !normalized.isEmpty() && !normalized.equals("{\"outcome\":\"UNRESOLVED\"}");
  }

  private static Msg message(String text, Map<String, Object> metadata) {
    return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).generateReason(GenerateReason.MODEL_STOP)
        .metadata(metadata).build();
  }
}
