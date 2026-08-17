package com.oc2.alexcoverage;

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

class AlexCoverageAdvisorAgentTest {
  private static final String UNFINISHED_EMPTY_SUMMARY = """
      {"reply": "请告诉我这份保单是医疗、意外还是重疾险，我好对照条款说明。",
       "done": false,
       "summary": {}}
      """;
  private static final String UNFINISHED_PARTIAL_SUMMARY = """
      {"reply": "请告诉我这次就诊的城市和医院，我好说明覆盖情况。",
       "done": false,
       "summary": {"city": "待确认", "visit_type": "待确认", "boundary": "尚未给出覆盖结论"}}
      """;
  private static final String FINISHED = """
      {"reply": "门诊理赔取决于保单条款、治疗类别与生效日期，这不是最终理赔结论。",
       "done": true,
       "summary": {"city": "上海", "facility": "三甲医院", "visit_type": "门诊",
                   "coverage_assessment": "按保单条款可能覆盖",
                   "coverage_conditions": "以生效日期和条款约定为准",
                   "source": "已批准产品材料", "boundary": "本步骤不作销售建议、核保决定或最终理赔决定"}}
      """;

  @Test void delegates_the_reply_to_the_host_model_with_a_coverage_system_prompt() {
    RecordingModel model = new RecordingModel(FINISHED);
    Msg response = new AlexCoverageAdvisorAgent(model).call(List.of(user("能理赔吗"))).block();

    assertEquals("门诊理赔取决于保单条款、治疗类别与生效日期，这不是最终理赔结论。", response.getTextContent());
    assertEquals(List.of(MsgRole.SYSTEM, MsgRole.USER), model.messages.stream().map(Msg::getRole).toList());
    String system = model.messages.getFirst().getTextContent();
    assertTrue(system.contains("personal-insurance coverage advisor"));
    assertTrue(system.contains("individually purchased medical"));
    assertTrue(system.contains("final claim decision"));
    assertTrue(system.contains("no-answer"));
    assertTrue(system.contains("strict JSON"));
    // The Host-registered recipient contract fields must be spelled out exactly in the instructions.
    assertTrue(system.contains("city"));
    assertTrue(system.contains("facility"));
    assertTrue(system.contains("visit_type"));
    assertTrue(system.contains("coverage_assessment"));
    assertTrue(system.contains("coverage_conditions"));
    assertTrue(system.contains("source"));
    assertTrue(system.contains("boundary"));
    assertFalse(system.contains("benefit journey"));
    assertFalse(system.contains("AQ Health Assistant"));
    assertFalse(system.contains("EBC Benefits Advisor"));
    assertFalse(system.contains("Main Bot"));
    assertFalse(system.contains("next step"));
    assertFalse(system.contains("handoff"));
  }

  @Test void keeps_the_step_open_without_presenting_an_empty_summary_when_done_is_false() {
    Msg response = new AlexCoverageAdvisorAgent(new RecordingModel(UNFINISHED_EMPTY_SUMMARY))
        .call(List.of(user("理赔"))).block();

    assertEquals("请告诉我这份保单是医疗、意外还是重疾险，我好对照条款说明。", response.getTextContent());
    // An empty summary must never be carried in the turn proposal: the Host bridge would reject an
    // empty summary object as an invalid proposal and fail the round instead of keeping it open.
    assertTrue(response.getMetadata().isEmpty());
    assertFalse(response.getMetadata().containsKey("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
  }

  @Test void keeps_the_step_open_and_attaches_a_contract_summary_without_lifecycle_when_done_is_false() {
    Msg response = new AlexCoverageAdvisorAgent(new RecordingModel(UNFINISHED_PARTIAL_SUMMARY))
        .call(List.of(user("理赔"))).block();

    assertEquals("请告诉我这次就诊的城市和医院，我好说明覆盖情况。", response.getTextContent());
    assertEquals(1, response.getMetadata().size());
    assertEquals(Map.of("summary", Map.of("city", "待确认", "visit_type", "待确认",
            "boundary", "尚未给出覆盖结论")),
        response.getMetadata().get("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
  }

  @Test void declares_completion_and_attaches_the_exact_contract_summary_when_done_is_true() {
    Msg response = new AlexCoverageAdvisorAgent(new RecordingModel(FINISHED)).call(List.of(user("理赔"))).block();

    assertTrue(response.getTextContent().contains("理赔"));
    assertEquals(1, response.getMetadata().size());
    assertEquals(Map.of("lifecycle", "COMPLETED",
            "summary", Map.of("city", "上海", "facility", "三甲医院", "visit_type", "门诊",
                "coverage_assessment", "按保单条款可能覆盖",
                "coverage_conditions", "以生效日期和条款约定为准",
                "source", "已批准产品材料", "boundary", "本步骤不作销售建议、核保决定或最终理赔决定")),
        response.getMetadata().get("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.summary"));
  }

  @Test void preserves_plain_text_as_an_unfinished_reply() {
    Msg response = new AlexCoverageAdvisorAgent(new RecordingModel("这需要看具体保单条款。"))
        .call(List.of(user("理赔"))).block();

    assertEquals("这需要看具体保单条款。", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_the_json_is_missing_required_fields() {
    Msg response = new AlexCoverageAdvisorAgent(new RecordingModel("{\"done\": false}"))
        .call(List.of(user("理赔"))).block();

    assertEquals("I couldn't generate coverage guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_done_true_summary_misses_contract_fields() {
    Msg response = new AlexCoverageAdvisorAgent(new RecordingModel("""
        {"reply": "可以结束了", "done": true, "summary": {"city": "上海"}}
        """)).call(List.of(user("理赔"))).block();

    assertEquals("I couldn't generate coverage guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_done_true_summary_has_an_unknown_field() {
    Msg response = new AlexCoverageAdvisorAgent(new RecordingModel("""
        {"reply": "可以结束了", "done": true,
         "summary": {"city": "上海", "facility": "三甲医院", "visit_type": "门诊",
                     "coverage_assessment": "可能覆盖", "coverage_conditions": "以条款为准",
                     "topic": "理赔", "boundary": "非最终理赔决定"}}
        """)).call(List.of(user("理赔"))).block();

    assertEquals("I couldn't generate coverage guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_done_true_summary_has_a_mistyped_field() {
    Msg response = new AlexCoverageAdvisorAgent(new RecordingModel("""
        {"reply": "可以结束了", "done": true,
         "summary": {"city": 123, "facility": "三甲医院", "visit_type": "门诊",
                     "coverage_assessment": "可能覆盖", "coverage_conditions": "以条款为准",
                     "source": "已批准产品材料", "boundary": "非最终理赔决定"}}
        """)).call(List.of(user("理赔"))).block();

    assertEquals("I couldn't generate coverage guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_the_model_cannot_respond() {
    Msg response = new AlexCoverageAdvisorAgent(null).call(List.of(user("理赔"))).block();

    assertEquals("I couldn't generate coverage guidance just now. Please try again.", response.getTextContent());
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
