package com.oc2.universityadvisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class UniversityAdvisorAgentTest {
  @Test void asks_bounded_questions_without_admission_or_current_data_claims() {
    String text = new UniversityAdvisorAgent(null).call(List.of(user("help me choose a university"))).block().getTextContent();
    assertTrue(text.contains("subjects or interests"));
    assertTrue(text.contains("academic fit"));
    assertTrue(text.contains("not an admission prediction"));
  }

  @Test void gives_illustrative_comparison_after_host_projected_advice_and_asks_to_continue() {
    String text = new UniversityAdvisorAgent(null).call(List.of(user("I enjoy biology"), assistant("For an illustrative comparison, group schools by academic fit."), user("what should I compare?"))).block().getTextContent();
    assertTrue(text.contains("academic fit"));
    assertTrue(text.contains("another question"));
  }

  @Test void emits_exact_completion_only_for_explicit_close() {
    UniversityAdvisorAgent agent = new UniversityAdvisorAgent(null);
    var ambiguous = agent.call(List.of(user("maybe that is enough, but tell me about location"))).block();
    assertFalse(ambiguous.getMetadata().containsKey("oc2.lifecycle_proposal"));
    var completed = agent.call(List.of(user("no further questions"))).block();
    assertEquals("COMPLETED", completed.getMetadata().get("oc2.lifecycle_proposal"));
  }

  @Test void interruption_is_empty_and_does_not_retain_history() {
    UniversityAdvisorAgent agent = new UniversityAdvisorAgent(null);
    agent.interrupt();
    assertEquals("", agent.call(List.of(user("no further questions"))).block().getTextContent());
    assertTrue(agent.call(List.of(user("hello"))).block().getTextContent().contains("academic fit"));
  }

  private static Msg user(String text) { return Msg.builder().role(MsgRole.USER).textContent(text).build(); }
  private static Msg assistant(String text) { return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build(); }
}
