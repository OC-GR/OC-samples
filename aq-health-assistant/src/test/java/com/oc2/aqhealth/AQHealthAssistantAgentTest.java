package com.oc2.aqhealth;

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

class AQHealthAssistantAgentTest {
  private static final String UNFINISHED = """
      {"reply": "请先告诉我症状和持续时间，我好给出一般性建议。",
       "done": false,
       "summary": {"health_summary": "正在收集症状信息", "duration": "", "urgency": "unknown"}}
      """;
  private static final String FINISHED = """
      {"reply": "已记录：反复旋转性头晕约一周，无呼吸困难和胸痛。若非紧急情况，可先休息观察；如加重请就医。",
       "done": true,
       "summary": {"health_summary": "反复旋转性头晕", "duration": "约一周", "urgency": "low"}}
      """;

  @Test void delegates_the_reply_to_the_host_model_with_a_non_diagnostic_system_prompt() {
    RecordingModel model = new RecordingModel(FINISHED);
    Msg response = new AQHealthAssistantAgent(model).call(List.of(user("我不舒服，能理赔吗"))).block();

    assertEquals("已记录：反复旋转性头晕约一周，无呼吸困难和胸痛。若非紧急情况，可先休息观察；如加重请就医。",
        response.getTextContent());
    assertEquals(List.of(MsgRole.SYSTEM, MsgRole.USER), model.messages.stream().map(Msg::getRole).toList());
    assertTrue(model.messages.getFirst().getTextContent().contains("non-diagnostic"));
    assertTrue(model.messages.getFirst().getTextContent().contains("safety-triage"));
    assertTrue(model.messages.getFirst().getTextContent().contains("Never diagnose"));
    assertTrue(model.messages.getFirst().getTextContent().contains("strict JSON"));
    assertTrue(model.messages.getFirst().getTextContent().contains("\"reply\": \"...\""));
  }

  @Test void keeps_the_step_open_without_a_lifecycle_proposal_when_done_is_false() {
    Msg response = new AQHealthAssistantAgent(new RecordingModel(UNFINISHED)).call(List.of(user("不舒服"))).block();

    assertEquals("请先告诉我症状和持续时间，我好给出一般性建议。", response.getTextContent());
    assertEquals(1, response.getMetadata().size());
    assertEquals(Map.of("summary", Map.of("health_summary", "正在收集症状信息", "duration", "", "urgency", "unknown")),
        response.getMetadata().get("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.summary"));
  }

  @Test void declares_completion_and_attaches_the_private_summary_when_done_is_true() {
    Msg response = new AQHealthAssistantAgent(new RecordingModel(FINISHED)).call(List.of(user("头晕一周"))).block();

    assertTrue(response.getTextContent().contains("头晕"));
    assertEquals(1, response.getMetadata().size());
    assertEquals(Map.of("lifecycle", "COMPLETED",
            "summary", Map.of("health_summary", "反复旋转性头晕", "duration", "约一周", "urgency", "low")),
        response.getMetadata().get("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.summary"));
  }

  @Test void returns_a_bounded_failure_message_when_the_model_output_is_not_json() {
    Msg response = new AQHealthAssistantAgent(new RecordingModel("我需要更多症状信息。"))
        .call(List.of(user("不舒服"))).block();

    assertEquals("I couldn't generate health guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_the_json_is_missing_required_fields() {
    Msg response = new AQHealthAssistantAgent(new RecordingModel("{\"reply\": \"你好\"}"))
        .call(List.of(user("不舒服"))).block();

    assertEquals("I couldn't generate health guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_the_model_cannot_respond() {
    Msg response = new AQHealthAssistantAgent(null).call(List.of(user("不舒服"))).block();

    assertEquals("I couldn't generate health guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
    assertTrue(!response.getMetadata().containsKey("oc2.lifecycle_proposal"));
    assertTrue(!response.getMetadata().containsKey("oc2.turn_proposal"));
    assertTrue(!response.getMetadata().containsKey("oc2.summary"));
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