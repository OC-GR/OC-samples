package com.oc2.hrpolicy;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Deterministic, read-only Native Agent. Host ownership begins at the AgentScope boundary. */
public final class HrPolicyAgent implements Agent {
  private final AtomicBoolean interrupted = new AtomicBoolean();

  public HrPolicyAgent(Model hostModel) { }

  @Override public String getAgentId() { return "hr-policy-guidance"; }
  @Override public String getName() { return "HR Policy Guidance"; }
  @Override public void interrupt() { interrupted.set(true); }
  @Override public void interrupt(Msg message) { interrupt(); }
  @Override public Mono<Void> observe(Msg message) { return Mono.empty(); }
  @Override public Mono<Void> observe(List<Msg> messages) { return Mono.empty(); }
  @Override public Mono<Msg> call(List<Msg> messages) { return Mono.just(result(messages)); }
  @Override public Mono<Msg> call(List<Msg> messages, Class<?> responseFormat) { return call(messages); }
  @Override public Mono<Msg> call(List<Msg> messages, com.fasterxml.jackson.databind.JsonNode options) { return call(messages); }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options) {
    return Flux.just(new Event(EventType.AGENT_RESULT, result(messages), true));
  }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options, Class<?> responseFormat) {
    return stream(messages, options);
  }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options, com.fasterxml.jackson.databind.JsonNode outputSchema) {
    return stream(messages, options);
  }

  private Msg result(List<Msg> messages) {
    if (interrupted.getAndSet(false)) return message("", GenerateReason.INTERRUPTED);
    String input = messages == null ? "" : messages.stream().filter(message -> message.getRole() == MsgRole.USER)
        .reduce((first, second) -> second).map(Msg::getTextContent).orElse("");
    String answer = PolicyCorpus.answerFor(input.toLowerCase(java.util.Locale.ROOT));
    return message(answer == null ? PolicyCorpus.NO_MATCH : answer, GenerateReason.MODEL_STOP);
  }

  private static Msg message(String text, GenerateReason reason) {
    return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).generateReason(reason).build();
  }
}
