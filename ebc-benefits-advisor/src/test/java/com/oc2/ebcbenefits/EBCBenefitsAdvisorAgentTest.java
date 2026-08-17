package com.oc2.ebcbenefits;

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

class EBCBenefitsAdvisorAgentTest {
  private static final String UNFINISHED = """
      {"reply": "需要先确认你的员工身份与授权，我才能查询你的福利信息。",
       "done": false,
       "summary": {"eligibility": "待确认", "coverage_scope": "", "rule_source": ""}}
      """;
  private static final String FINISHED = """
      {"reply": "常见福利包括门诊、住院与补充健康项目，具体以当前计划文件为准。",
       "done": true,
       "summary": {"eligibility": "在职员工", "coverage_scope": "门诊、住院、补充健康",
                   "annual_limit": "以计划文件为准", "remaining_balance": "以计划文件为准",
                   "validity": "本年度", "rule_source": "当前员工福利计划文件"}}
      """;

  @Test void delegates_the_reply_to_the_host_model_with_a_benefits_system_prompt() {
    RecordingModel model = new RecordingModel(FINISHED);
    Msg response = new EBCBenefitsAdvisorAgent(model).call(List.of(user("福利有哪些"))).block();

    assertEquals("常见福利包括门诊、住院与补充健康项目，具体以当前计划文件为准。", response.getTextContent());
    assertEquals(List.of(MsgRole.SYSTEM, MsgRole.USER), model.messages.stream().map(Msg::getRole).toList());
    assertTrue(model.messages.getFirst().getTextContent().contains("group insurance"));
    assertTrue(model.messages.getFirst().getTextContent().contains("group insurance for employees"));
    assertTrue(model.messages.getFirst().getTextContent().contains("consented"));
    assertTrue(model.messages.getFirst().getTextContent().contains("remaining balances"));
    assertTrue(model.messages.getFirst().getTextContent().contains("strict JSON"));
  }

  @Test void keeps_the_step_open_without_a_lifecycle_proposal_when_done_is_false() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel(UNFINISHED)).call(List.of(user("福利"))).block();

    assertEquals("需要先确认你的员工身份与授权，我才能查询你的福利信息。", response.getTextContent());
    assertEquals(1, response.getMetadata().size());
    assertEquals(Map.of("summary", Map.of("eligibility", "待确认", "coverage_scope", "", "rule_source", "")),
        response.getMetadata().get("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.summary"));
  }

  @Test void declares_completion_and_attaches_the_private_summary_when_done_is_true() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel(FINISHED)).call(List.of(user("福利"))).block();

    assertTrue(response.getTextContent().contains("门诊"));
    assertEquals(1, response.getMetadata().size());
    assertEquals(Map.of("lifecycle", "COMPLETED",
            "summary", Map.of("eligibility", "在职员工", "coverage_scope", "门诊、住院、补充健康",
                "annual_limit", "以计划文件为准", "remaining_balance", "以计划文件为准",
                "validity", "本年度", "rule_source", "当前员工福利计划文件")),
        response.getMetadata().get("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.summary"));
  }

  @Test void returns_a_bounded_failure_message_when_the_model_output_is_not_json() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel("请提供员工编号后再查询。"))
        .call(List.of(user("福利"))).block();

    assertEquals("I couldn't generate benefits guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_the_json_is_missing_required_fields() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel("{\"summary\": {}}"))
        .call(List.of(user("福利"))).block();

    assertEquals("I couldn't generate benefits guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_the_model_cannot_respond() {
    Msg response = new EBCBenefitsAdvisorAgent(null).call(List.of(user("福利"))).block();

    assertEquals("I couldn't generate benefits guidance just now. Please try again.", response.getTextContent());
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