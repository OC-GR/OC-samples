package com.oc2.universityadvisor;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Stateless illustrative school-selection advisor; the Host owns all context and lifecycle authority. */
public final class UniversityAdvisorAgent implements Agent {
  private final Model model;
  private final AtomicBoolean interrupted = new AtomicBoolean();

  public UniversityAdvisorAgent(Model model) { this.model = model; }
  @Override public String getAgentId() { return "university-advisor"; }
  @Override public String getName() { return "University Advisor"; }
  @Override public void interrupt() { interrupted.set(true); }
  @Override public void interrupt(Msg message) { interrupt(); }
  @Override public Mono<Void> observe(Msg message) { return Mono.empty(); }
  @Override public Mono<Void> observe(List<Msg> messages) { return Mono.empty(); }
  @Override public Mono<Msg> call(List<Msg> messages) { return Mono.just(result(messages)); }
  @Override public Mono<Msg> call(List<Msg> messages, Class<?> responseFormat) { return call(messages); }
  @Override public Mono<Msg> call(List<Msg> messages, com.fasterxml.jackson.databind.JsonNode schema) { return call(messages); }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options) {
    return consultModel(messages).thenMany(Flux.defer(() -> Flux.just(new Event(EventType.AGENT_RESULT, result(messages), true))));
  }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options, Class<?> responseFormat) { return stream(messages, options); }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options, com.fasterxml.jackson.databind.JsonNode schema) { return stream(messages, options); }

  private Mono<Void> consultModel(List<Msg> messages) {
    if (model == null || interrupted.get()) return Mono.empty();
    return model.stream(messages == null ? List.of() : messages, List.of(), null).then().onErrorResume(error -> Mono.empty());
  }

  private Msg result(List<Msg> messages) {
    if (interrupted.getAndSet(false)) return message("", Map.of());
    String current = latestUser(messages).toLowerCase(Locale.ROOT);
    if (explicitClose(current)) return message("I will close this illustrative school-selection discussion. It does not predict admission or provide current institution, financial, legal, or medical advice.",
        Map.of("oc2.lifecycle_proposal", "COMPLETED"));
    return message("For an illustrative comparison, group schools by academic fit, learning environment, location, support needs, and practical constraints. What subjects or interests are most important, what academic environment do you prefer, and what location or practical constraints should shape the comparison? Verify current details directly with each institution; this is not an admission prediction or financial, legal, or medical advice. Is there another question about your school-selection priorities?", Map.of());
  }

  private static boolean explicitClose(String value) {
    return value.matches("(?s).*\\b(no further questions?|no more questions?|nothing else|that(?:'s| is) all|i(?:'m| am) done|close)\\b.*");
  }
  private static String latestUser(List<Msg> messages) {
    return messages == null ? "" : messages.stream().filter(message -> message.getRole() == MsgRole.USER)
        .reduce((first, second) -> second).map(Msg::getTextContent).orElse("");
  }
  private static Msg message(String text, Map<String, Object> metadata) {
    return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).generateReason(GenerateReason.MODEL_STOP).metadata(metadata).build();
  }
}
