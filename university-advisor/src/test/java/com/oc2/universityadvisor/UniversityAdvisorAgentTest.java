package com.oc2.universityadvisor;

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

class UniversityAdvisorAgentTest {
  @Test void sends_instructions_then_the_host_projected_role_preserving_history_to_the_model() {
    RecordingModel model = new RecordingModel("模型建议");
    new UniversityAdvisorAgent(model).call(List.of(user("想读计算机"), assistant("之前的回复"), user("地点上海"))).block();

    assertEquals(List.of(MsgRole.SYSTEM, MsgRole.USER, MsgRole.ASSISTANT, MsgRole.USER),
        model.messages.stream().map(Msg::getRole).toList());
    assertTrue(model.messages.getFirst().getTextContent().contains("university selection advisor"));
    assertTrue(model.messages.getFirst().getTextContent().contains("[[OC2:REQUEST_COMPLETED]]"));
    assertTrue(model.messages.getFirst().getTextContent().contains("You alone decide"));
    assertEquals("地点上海", model.messages.getLast().getTextContent());
  }

  @Test void returns_the_model_generated_advice_without_a_hardcoded_subject_branch() {
    String expected = "建议按课程、师生互动、预算和官方信息核验比较三类学校。你对专业、地区或大学择校还有其他问题吗？";
    String actual = new UniversityAdvisorAgent(new RecordingModel(expected)).call(List.of(user("我想读 AI，地点上海")))
        .block().getTextContent();
    assertEquals(expected, actual);
  }

  @Test void strips_marker_and_completes_when_the_model_requests_completion() {
    Msg response = new UniversityAdvisorAgent(new RecordingModel("祝你择校顺利。\n[[OC2:REQUEST_COMPLETED]]"))
        .call(List.of(user("我想读计算机")))
        .block();
    assertEquals("祝你择校顺利。", response.getTextContent());
    assertEquals("COMPLETED", response.getMetadata().get("oc2.lifecycle_proposal"));
  }

  @Test void marker_is_hidden_and_model_completion_is_not_overridden_by_host_heuristics() {
    Msg response = new UniversityAdvisorAgent(new RecordingModel("建议继续补充。[[OC2:REQUEST_COMPLETED]]"))
        .call(List.of(user("没有了"))).block();
    assertEquals("建议继续补充。", response.getTextContent());
    assertEquals("COMPLETED", response.getMetadata().get("oc2.lifecycle_proposal"));
  }

  @Test void unresolved_or_unavailable_model_completes_with_a_model_failure_message() {
    Msg response = new UniversityAdvisorAgent(new RecordingModel("{\"outcome\":\"UNRESOLVED\"}"))
        .call(List.of(user("我想在上海读大学"))).block();
    assertEquals("模型调用失败，本次咨询已结束。", response.getTextContent());
    assertEquals("COMPLETED", response.getMetadata().get("oc2.lifecycle_proposal"));
  }

  @Test void interruption_is_empty_and_does_not_retain_history() {
    UniversityAdvisorAgent agent = new UniversityAdvisorAgent(null);
    agent.interrupt();
    assertEquals("", agent.call(List.of(user("hello"))).block().getTextContent());
    Msg failure = agent.call(List.of(user("hello"))).block();
    assertEquals("模型调用失败，本次咨询已结束。", failure.getTextContent());
    assertEquals("COMPLETED", failure.getMetadata().get("oc2.lifecycle_proposal"));
  }

  private static Msg user(String text) { return Msg.builder().role(MsgRole.USER).textContent(text).build(); }
  private static Msg assistant(String text) { return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build(); }

  private static final class RecordingModel implements Model {
    private final List<String> texts;
    private List<Msg> messages = List.of();
    private RecordingModel(String... texts) { this.texts = List.of(texts); }
    @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
      this.messages = List.copyOf(messages);
      String text = texts.getFirst();
      return Flux.just(new ChatResponse("test", List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop"));
    }
    @Override public String getModelName() { return "test"; }
  }
}
