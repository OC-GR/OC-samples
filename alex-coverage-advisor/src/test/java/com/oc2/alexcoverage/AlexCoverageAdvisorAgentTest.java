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
  private static final String UNFINISHED = """
      {"reply": "请告诉我这份保单是医疗、意外还是重疾险，我好对照条款说明。",
       "done": false,
       "summary": {"topic": "理赔条件", "coverage_summary": "等待用户补充险种信息", "open_items": ["险种"]}}
      """;
  private static final String FINISHED = """
      {"reply": "理赔取决于保单条款、治疗类别与生效日期，这不是最终理赔结论。",
       "done": true,
       "summary": {"topic": "理赔条件", "coverage_summary": "已说明理赔取决于条款、治疗类别与生效日期",
                   "open_items": []}}
      """;

  @Test void delegates_the_reply_to_the_host_model_with_a_coverage_system_prompt() {
    RecordingModel model = new RecordingModel(FINISHED);
    Msg response = new AlexCoverageAdvisorAgent(model).call(List.of(user("能理赔吗"))).block();

    assertEquals("理赔取决于保单条款、治疗类别与生效日期，这不是最终理赔结论。", response.getTextContent());
    assertEquals(List.of(MsgRole.SYSTEM, MsgRole.USER), model.messages.stream().map(Msg::getRole).toList());
    assertTrue(model.messages.getFirst().getTextContent().contains("Personal insurance"));
    assertTrue(model.messages.getFirst().getTextContent().contains("medical insurance"));
    assertTrue(model.messages.getFirst().getTextContent().contains("final claim decision"));
    assertTrue(model.messages.getFirst().getTextContent().contains("no-answer"));
    assertTrue(model.messages.getFirst().getTextContent().contains("strict JSON"));
  }

  @Test void keeps_the_step_open_without_a_lifecycle_proposal_when_done_is_false() {
    Msg response = new AlexCoverageAdvisorAgent(new RecordingModel(UNFINISHED)).call(List.of(user("理赔"))).block();

    assertEquals("请告诉我这份保单是医疗、意外还是重疾险，我好对照条款说明。", response.getTextContent());
    assertEquals(1, response.getMetadata().size());
    assertEquals(Map.of("summary", Map.of("topic", "理赔条件", "coverage_summary", "等待用户补充险种信息", "open_items", List.of("险种"))),
        response.getMetadata().get("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.summary"));
  }

  @Test void declares_completion_and_attaches_the_private_summary_when_done_is_true() {
    Msg response = new AlexCoverageAdvisorAgent(new RecordingModel(FINISHED)).call(List.of(user("理赔"))).block();

    assertTrue(response.getTextContent().contains("理赔"));
    assertEquals(1, response.getMetadata().size());
    assertEquals(Map.of("lifecycle", "COMPLETED",
            "summary", Map.of("topic", "理赔条件", "coverage_summary", "已说明理赔取决于条款、治疗类别与生效日期", "open_items", List.of())),
        response.getMetadata().get("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.summary"));
  }

  @Test void returns_a_bounded_failure_message_when_the_model_output_is_not_json() {
    Msg response = new AlexCoverageAdvisorAgent(new RecordingModel("这需要看具体保单条款。"))
        .call(List.of(user("理赔"))).block();

    assertEquals("I couldn't generate coverage guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_the_json_is_missing_required_fields() {
    Msg response = new AlexCoverageAdvisorAgent(new RecordingModel("{\"done\": false}"))
        .call(List.of(user("理赔"))).block();

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