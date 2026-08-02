package com.oc2.itsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItSupportGuideAgentTest {
  @Test void returns_synthetic_support_without_a_model_or_system_access() {
    ItSupportGuideAgent agent = new ItSupportGuideAgent(null);
    assertEquals(true, agent.call(List.of(user("How do I use the VPN?"))).block().getTextContent().contains("cannot inspect or change"));
    assertEquals(true, agent.call(List.of(user("Can you reset my password?"))).block().getTextContent().contains("cannot reset passwords"));
  }

  @Test void emits_one_final_result_and_honors_interruption() {
    ItSupportGuideAgent agent = new ItSupportGuideAgent(null);
    var events = agent.stream(List.of(user("install software")), StreamOptions.defaults()).collectList().block();
    assertEquals(1, events.size());
    assertEquals(EventType.AGENT_RESULT, events.getFirst().getType());
    agent.interrupt();
    assertEquals("", agent.call(List.of(user("VPN"))).block().getTextContent());
  }

  private static Msg user(String text) { return Msg.builder().role(MsgRole.USER).textContent(text).build(); }
}
