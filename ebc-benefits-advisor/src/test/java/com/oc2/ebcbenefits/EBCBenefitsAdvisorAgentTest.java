package com.oc2.ebcbenefits;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class EBCBenefitsAdvisorAgentTest {
  @Test void delegates_the_reply_to_the_host_model_with_a_benefits_system_prompt() {
    RecordingModel model = new RecordingModel("常见福利包括门诊、住院与补充健康项目，具体以当前计划文件为准。");
    Msg response = new EBCBenefitsAdvisorAgent(model).call(List.of(user("福利有哪些"))).block();

    assertEquals("常见福利包括门诊、住院与补充健康项目，具体以当前计划文件为准。", response.getTextContent());
    assertEquals(List.of(MsgRole.SYSTEM, MsgRole.USER), model.messages.stream().map(Msg::getRole).toList());
    assertTrue(model.messages.getFirst().getTextContent().contains("group insurance"));
    assertTrue(model.messages.getFirst().getTextContent().contains("group insurance for employees"));
    assertTrue(model.messages.getFirst().getTextContent().contains("consented"));
    assertTrue(model.messages.getFirst().getTextContent().contains("remaining balances"));
  }

  @Test void returns_a_bounded_failure_message_when_the_model_cannot_respond() {
    Msg response = new EBCBenefitsAdvisorAgent(null).call(List.of(user("福利"))).block();

    assertEquals("I couldn't generate benefits guidance just now. Please try again.", response.getTextContent());
    assertTrue(!response.getMetadata().containsKey("oc2.lifecycle_proposal"));
    assertTrue(!response.getMetadata().containsKey("oc2.turn_proposal"));
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
