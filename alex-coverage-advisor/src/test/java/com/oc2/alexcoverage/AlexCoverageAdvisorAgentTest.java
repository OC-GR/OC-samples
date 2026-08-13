package com.oc2.alexcoverage;

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

class AlexCoverageAdvisorAgentTest {
  @Test void delegates_the_reply_to_the_host_model_with_a_coverage_system_prompt() {
    RecordingModel model = new RecordingModel("理赔取决于保单条款、治疗类别与生效日期，这不是最终理赔结论。");
    Msg response = new AlexCoverageAdvisorAgent(model).call(List.of(user("能理赔吗"))).block();

    assertEquals("理赔取决于保单条款、治疗类别与生效日期，这不是最终理赔结论。", response.getTextContent());
    assertEquals(List.of(MsgRole.SYSTEM, MsgRole.USER), model.messages.stream().map(Msg::getRole).toList());
    assertTrue(model.messages.getFirst().getTextContent().contains("Personal insurance"));
    assertTrue(model.messages.getFirst().getTextContent().contains("medical insurance"));
    assertTrue(model.messages.getFirst().getTextContent().contains("final claim decision"));
    assertTrue(model.messages.getFirst().getTextContent().contains("no-answer"));
  }

  @Test void returns_a_bounded_failure_message_when_the_model_cannot_respond() {
    Msg response = new AlexCoverageAdvisorAgent(null).call(List.of(user("理赔"))).block();

    assertEquals("I couldn't generate coverage guidance just now. Please try again.", response.getTextContent());
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
