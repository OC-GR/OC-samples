package com.oc2.travelconsultant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class TravelConsultantAgentTest {
  @Test void delegates_advice_and_state_to_the_host_model() {
    RecordingModel model = new RecordingModel("{\"reply\":\"Where would you like to travel?\",\"state\":{\"interests\":\"food\"},\"completed\":false}");
    Msg response = new TravelConsultantAgent(model).call(List.of(user("Plan a trip"))).block();

    assertEquals("Where would you like to travel?", response.getTextContent());
    assertEquals(List.of(MsgRole.SYSTEM, MsgRole.USER), model.messages.stream().map(Msg::getRole).toList());
    assertTrue(model.messages.getFirst().getTextContent().contains("exactly one JSON object"));
    assertTrue(model.messages.getFirst().getTextContent().contains("live pricing"));
    assertTrue(model.messages.getFirst().getTextContent().contains("不需要更多建议"));
    assertTrue(model.messages.getFirst().getTextContent().contains("complete itinerary"));
    Map<?, ?> proposal = (Map<?, ?>) response.getMetadata().get("oc2.turn_proposal");
    assertEquals(Map.of("interests", "food"), proposal.get("state"));
    assertFalse(proposal.containsKey("lifecycle"));
  }

  @Test void accepts_only_the_closed_model_response_and_maps_completion_to_the_private_proposal() {
    Msg response = new TravelConsultantAgent(new RecordingModel("{\"reply\":\"Your outline is ready.\",\"state\":{\"destination\":\"Kyoto\"},\"completed\":true}"))
        .call(List.of(user("That is all"))).block();

    Map<?, ?> proposal = (Map<?, ?>) response.getMetadata().get("oc2.turn_proposal");
    assertEquals("COMPLETED", proposal.get("lifecycle"));
    assertEquals(Map.of("destination", "Kyoto"), proposal.get("state"));
  }

  @Test void rejects_malformed_model_output_without_falling_back_to_simulated_advice() {
    Msg response = new TravelConsultantAgent(new RecordingModel("Illustrative outline for Kyoto"))
        .call(List.of(user("Plan Kyoto"))).block();

    assertEquals("I couldn't generate travel guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  private static Msg user(String text) { return Msg.builder().role(MsgRole.USER).textContent(text).build(); }

  private static final class RecordingModel implements Model {
    private final String text;
    private List<Msg> messages = List.of();
    private RecordingModel(String text) { this.text = text; }
    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
      this.messages = List.copyOf(messages);
      return Flux.just(new ChatResponse("test", List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop"));
    }
    @Override public String getModelName() { return "test"; }
  }
}
