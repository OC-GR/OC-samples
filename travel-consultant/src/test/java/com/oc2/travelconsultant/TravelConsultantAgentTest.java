package com.oc2.travelconsultant;

import static org.junit.jupiter.api.Assertions.*;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class TravelConsultantAgentTest {
  @Test void asks_for_a_bounded_missing_preference_and_never_claims_booking() {
    String text = new TravelConsultantAgent(null).call(List.of(user("I want a trip"))).block().getTextContent();
    assertTrue(text.contains("destination or region")); assertTrue(text.toLowerCase().contains("cannot check live pricing"));
  }
  @Test void emits_one_final_state_proposal_and_closes_only_with_explicit_close() {
    TravelConsultantAgent agent = new TravelConsultantAgent(null);
    String state = "{\"oc2_context_version\":1,\"state\":{\"destination\":\"kyoto\",\"dates\":\"flexible\",\"party\":\"2\",\"budget\":\"moderate\",\"interests\":\"food\"}}";
    var open = agent.stream(List.of(system(state), user("please refine")), StreamOptions.defaults()).collectList().block();
    assertEquals(1, open.size()); assertEquals(EventType.AGENT_RESULT, open.getFirst().getType());
    assertTrue(open.getFirst().getMessage().getTextContent().contains("Illustrative outline for kyoto"));
    assertFalse(open.getFirst().getMessage().getMetadata().toString().contains("COMPLETED"));
    var closed = agent.call(List.of(system(state), user("close"))).block();
    assertEquals("COMPLETED", ((java.util.Map<?, ?>) closed.getMetadata().get("oc2.turn_proposal")).get("lifecycle"));
  }
  @Test void interruption_is_empty_and_does_not_retain_state() {
    TravelConsultantAgent agent = new TravelConsultantAgent(null); agent.interrupt();
    assertEquals("", agent.call(List.of(user("to Kyoto"))).block().getTextContent());
    assertTrue(agent.call(List.of(user("to Kyoto"))).block().getTextContent().contains("dates or flexibility"));
  }
  private static Msg user(String text) { return Msg.builder().role(MsgRole.USER).textContent(text).build(); }
  private static Msg system(String text) { return Msg.builder().role(MsgRole.SYSTEM).textContent(text).build(); }
}
