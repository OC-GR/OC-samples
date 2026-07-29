package com.oc2.travelconsultant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Stateless illustrative travel advisor. Its only authority is the Host Model and Host-projected messages. */
public final class TravelConsultantAgent implements Agent {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Model model;
  private final AtomicBoolean interrupted = new AtomicBoolean();

  public TravelConsultantAgent(Model model) { this.model = model; }
  @Override public String getAgentId() { return "travel-consultant"; }
  @Override public String getName() { return "Travel Consultant"; }
  @Override public void interrupt() { interrupted.set(true); }
  @Override public void interrupt(Msg message) { interrupt(); }
  @Override public Mono<Void> observe(Msg message) { return Mono.empty(); }
  @Override public Mono<Void> observe(List<Msg> messages) { return Mono.empty(); }
  @Override public Mono<Msg> call(List<Msg> messages) { return Mono.just(result(messages)); }
  @Override public Mono<Msg> call(List<Msg> messages, Class<?> responseFormat) { return call(messages); }
  @Override public Mono<Msg> call(List<Msg> messages, com.fasterxml.jackson.databind.JsonNode schema) { return call(messages); }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options) {
    return Flux.defer(() -> Flux.just(new Event(EventType.AGENT_RESULT, result(messages), true)));
  }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options, Class<?> responseFormat) { return stream(messages, options); }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options, com.fasterxml.jackson.databind.JsonNode schema) { return stream(messages, options); }

  private Msg result(List<Msg> messages) {
    if (interrupted.getAndSet(false)) return message("", Map.of());
    Map<String, String> state = state(messages);
    String current = latestUser(messages).toLowerCase(Locale.ROOT);
    if (isClose(current) && sufficient(state)) {
      return message("Your illustrative travel outline is complete. It is not live pricing, availability, booking, or travel, visa, legal, medical, safety, or financial advice.",
          Map.of("oc2.turn_proposal", Map.of("state", state, "lifecycle", "COMPLETED")));
    }
    capture(state, current);
    String missing = missing(state);
    if (missing != null) return message("To keep this illustrative, what " + missing + " should I use for the trip? I cannot check live pricing or availability or make bookings.", proposal(state));
    return message("Illustrative outline for " + state.get("destination") + ": choose a relaxed first day, two interest-led days, and a flexible local day. Plan for "
        + state.get("party") + " with a " + state.get("budget") + " budget around " + state.get("dates") + ". This is planning guidance only, not live pricing, availability, booking, or travel advice. Reply with a correction, refinement, or say close when you are done.", proposal(state));
  }

  private static Map<String, Object> proposal(Map<String, String> state) { return Map.of("oc2.turn_proposal", Map.of("state", state)); }
  private static Msg message(String text, Map<String, Object> metadata) { return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).generateReason(GenerateReason.MODEL_STOP).metadata(metadata).build(); }
  private static boolean isClose(String value) { return value.matches("(?s).*\\b(close|done|finish|finished|that's all|that is all)\\b.*"); }
  private static boolean sufficient(Map<String, String> state) { return missing(state) == null; }
  private static String missing(Map<String, String> state) {
    for (String key : List.of("destination", "dates", "party", "budget", "interests")) if (!state.containsKey(key)) return switch (key) {
      case "destination" -> "destination or region"; case "dates" -> "dates or flexibility"; case "party" -> "party size";
      case "budget" -> "budget range"; default -> "interests"; };
    return null;
  }
  private static void capture(Map<String, String> state, String current) {
    if (!state.containsKey("destination") && current.matches(".*\\b(to|in|visit|travel)\\s+[a-z][a-z -]{2,30}.*")) state.put("destination", current.replaceFirst(".*\\b(to|in|visit|travel)\\s+([a-z][a-z -]{2,30}).*", "$2").trim());
    if (!state.containsKey("dates") && (current.contains("date") || current.contains("week") || current.contains("month") || current.contains("flexible"))) state.put("dates", current.length() > 80 ? "flexible" : current);
    if (!state.containsKey("party") && current.matches(".*\\b([1-9]|solo|couple|family|friends)\\b.*")) state.put("party", current.matches(".*\\bcouple\\b.*") ? "2" : current.matches(".*\\bsolo\\b.*") ? "1" : "group");
    if (!state.containsKey("budget") && (current.contains("budget") || current.contains("cheap") || current.contains("moderate") || current.contains("luxury"))) state.put("budget", current.contains("luxury") ? "higher" : current.contains("cheap") ? "lower" : "moderate");
    if (!state.containsKey("interests") && (current.contains("food") || current.contains("museum") || current.contains("nature") || current.contains("beach") || current.contains("history"))) state.put("interests", current.contains("food") ? "food" : current.contains("museum") ? "museums" : current.contains("nature") ? "nature" : "local exploration");
  }
  private static String latestUser(List<Msg> messages) { return messages == null ? "" : messages.stream().filter(msg -> msg.getRole() == MsgRole.USER).reduce((a, b) -> b).map(Msg::getTextContent).orElse(""); }
  private static Map<String, String> state(List<Msg> messages) {
    Map<String, String> state = new LinkedHashMap<>();
    if (messages == null) return state;
    messages.stream().map(Msg::getTextContent).filter(value -> value != null).map(TravelConsultantAgent::stateNode)
        .filter(java.util.Objects::nonNull).findFirst().ifPresent(node -> {
          for (String key : List.of("destination", "dates", "party", "budget", "interests")) {
            JsonNode value = node.path(key);
            if (value.isTextual() && value.textValue().length() <= 128) state.put(key, value.textValue());
          }
        });
    return state;
  }

  private static JsonNode stateNode(String raw) {
    try {
      JsonNode envelope = JSON.readTree(raw);
      for (int depth = 0; envelope != null && envelope.isTextual() && depth < 2; depth++) envelope = JSON.readTree(envelope.textValue());
      if (envelope == null || !envelope.path("oc2_context_version").canConvertToInt()
          || envelope.path("oc2_context_version").asInt() != 1) return null;
      JsonNode state = envelope.path("state");
      for (int depth = 0; state.isTextual() && depth < 2; depth++) state = JSON.readTree(state.textValue());
      return state.isObject() ? state : null;
    } catch (Exception ignored) { return null; }
  }
}
