package com.oc2.hrpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class HrPolicyAgentTest {
  @Test void returns_supported_and_bounded_no_match_results_without_a_model() {
    HrPolicyAgent agent = new HrPolicyAgent(null);
    assertEquals("Annual leave requests must be submitted through the approved HR process before the requested absence. Contact HR for your current balance and local eligibility.",
        agent.call(List.of(user("How do I request annual leave?"))).block().getTextContent());
    assertEquals(PolicyCorpus.NO_MATCH, agent.call(List.of(user("What is the office coffee policy?"))).block().getTextContent());
  }

  @Test void emits_one_final_result_and_honors_interruption() {
    HrPolicyAgent agent = new HrPolicyAgent(null);
    var events = agent.stream(List.of(user("sick leave")), StreamOptions.defaults()).collectList().block();
    assertEquals(1, events.size());
    assertEquals(EventType.AGENT_RESULT, events.getFirst().getType());
    agent.interrupt();
    assertEquals("", agent.call(List.of(user("annual leave"))).block().getTextContent());
  }

  private static Msg user(String text) { return Msg.builder().role(MsgRole.USER).textContent(text).build(); }
}
