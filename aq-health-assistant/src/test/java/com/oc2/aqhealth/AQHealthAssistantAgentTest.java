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
  private static final String UNFINISHED_EMPTY_SUMMARY = """
      {"reply": "请先告诉我症状和持续时间，我好给出一般性建议。",
       "done": false,
       "summary": {}}
      """;
  private static final String UNFINISHED_PARTIAL_SUMMARY = """
      {"reply": "请先告诉我症状持续了多久。",
       "done": false,
       "summary": {"health_summary": "正在收集症状信息", "reported_red_flags": false,
                   "boundary": "尚未给出任何建议"}}
      """;
  private static final String FINISHED = """
      {"reply": "已记录：反复旋转性头晕约一周，无呼吸困难或胸痛。若非紧急情况，可先休息观察；如加重请就医。",
       "done": true,
       "summary": {"health_summary": "反复旋转性头晕约一周，无胸痛或呼吸困难",
                   "reported_red_flags": false,
                   "care_recommendation": "如症状加重请及时就医；目前无需紧急处理",
                   "boundary": "本步骤不作诊断、不处方、不决定治疗"}}
      """;

  @Test void delegates_the_reply_to_the_host_model_with_a_non_diagnostic_system_prompt() {
    RecordingModel model = new RecordingModel(FINISHED);
    Msg response = new AQHealthAssistantAgent(model).call(List.of(user("我不舒服，能理赔吗"))).block();

    assertEquals("已记录：反复旋转性头晕约一周，无呼吸困难或胸痛。若非紧急情况，可先休息观察；如加重请就医。",
        response.getTextContent());
    assertEquals(List.of(MsgRole.SYSTEM, MsgRole.USER), model.messages.stream().map(Msg::getRole).toList());
    String system = model.messages.getFirst().getTextContent();
    assertTrue(system.contains("non-diagnostic"));
    assertTrue(system.contains("safety-triage"));
    assertTrue(system.contains("Never diagnose"));
    assertTrue(system.contains("strict JSON"));
    assertTrue(system.contains("\"reply\": \"...\""));
    // The Host-registered recipient contract fields must be spelled out exactly in the instructions.
    assertTrue(system.contains("health_summary"));
    assertTrue(system.contains("reported_red_flags"));
    assertTrue(system.contains("care_recommendation"));
    assertTrue(system.contains("boundary"));
    assertFalse(system.contains("benefit journey"));
    assertFalse(system.contains("insurance"));
    assertFalse(system.contains("other agents"));
    assertFalse(system.contains("Main Bot"));
    assertFalse(system.contains("next step"));
    assertFalse(system.contains("handoff"));
    assertFalse(system.contains("Alex"));
    assertFalse(system.contains("EBC"));
  }

  @Test void keeps_the_step_open_without_presenting_an_empty_summary_when_done_is_false() {
    Msg response = new AQHealthAssistantAgent(new RecordingModel(UNFINISHED_EMPTY_SUMMARY))
        .call(List.of(user("不舒服"))).block();

    assertEquals("请先告诉我症状和持续时间，我好给出一般性建议。", response.getTextContent());
    // An empty summary must never be carried in the turn proposal: the Host bridge would reject an
    // empty summary object as an invalid proposal and fail the round instead of keeping it open.
    assertTrue(response.getMetadata().isEmpty());
    assertFalse(response.getMetadata().containsKey("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
  }

  @Test void keeps_the_step_open_and_attaches_a_contract_summary_without_lifecycle_when_done_is_false() {
    Msg response = new AQHealthAssistantAgent(new RecordingModel(UNFINISHED_PARTIAL_SUMMARY))
        .call(List.of(user("不舒服"))).block();

    assertEquals("请先告诉我症状持续了多久。", response.getTextContent());
    assertEquals(1, response.getMetadata().size());
    assertEquals(Map.of("summary", Map.of("health_summary", "正在收集症状信息",
            "reported_red_flags", false, "boundary", "尚未给出任何建议")),
        response.getMetadata().get("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
  }

  @Test void declares_completion_and_attaches_the_exact_contract_summary_when_done_is_true() {
    Msg response = new AQHealthAssistantAgent(new RecordingModel(FINISHED)).call(List.of(user("头晕一周"))).block();

    assertTrue(response.getTextContent().contains("头晕"));
    assertEquals(1, response.getMetadata().size());
    assertEquals(Map.of("lifecycle", "COMPLETED",
            "summary", Map.of("health_summary", "反复旋转性头晕约一周，无胸痛或呼吸困难",
                "reported_red_flags", false,
                "care_recommendation", "如症状加重请及时就医；目前无需紧急处理",
                "boundary", "本步骤不作诊断、不处方、不决定治疗")),
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

  @Test void returns_a_bounded_failure_message_when_done_true_summary_misses_contract_fields() {
    Msg response = new AQHealthAssistantAgent(new RecordingModel("""
        {"reply": "可以结束了", "done": true, "summary": {"health_summary": "头晕"}}
        """)).call(List.of(user("不舒服"))).block();

    assertEquals("I couldn't generate health guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_done_true_summary_has_an_unknown_field() {
    Msg response = new AQHealthAssistantAgent(new RecordingModel("""
        {"reply": "可以结束了", "done": true,
         "summary": {"health_summary": "头晕", "reported_red_flags": false,
                     "care_recommendation": "就医", "duration": "一周"}}
        """)).call(List.of(user("不舒服"))).block();

    assertEquals("I couldn't generate health guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_done_true_summary_has_a_mistyped_field() {
    Msg response = new AQHealthAssistantAgent(new RecordingModel("""
        {"reply": "可以结束了", "done": true,
         "summary": {"health_summary": "头晕", "reported_red_flags": "否",
                     "care_recommendation": "就医", "boundary": "非诊断"}}
        """)).call(List.of(user("不舒服"))).block();

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
