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
  private static final String UNFINISHED_EMPTY_SUMMARY = """
      {"reply": "需要先确认你的员工身份与授权，我才能查询你的福利信息。",
       "done": false,
       "summary": {}}
      """;
  private static final String UNFINISHED_PARTIAL_SUMMARY = """
      {"reply": "身份确认后即可查询福利与余额信息。",
       "done": false,
       "summary": {"source": "当前员工福利计划文件", "boundary": "身份或授权确认后才可查询"}}
      """;
  private static final String FINISHED = """
      {"reply": "已查询到：门诊可直付、余额 3500 MYR、特需需报销，具体以当前计划文件为准。",
       "done": true,
       "summary": {"direct_pay_available": true, "direct_pay_scope": "门诊和常规住院",
                   "reimbursement_required_for": "特需和自费项目",
                   "remaining_balance": 3500, "currency": "MYR",
                   "submission_deadline_days": 90,
                   "required_materials": ["发票", "处方"],
                   "source": "当前员工福利计划文件",
                   "boundary": "本步骤不作最终理赔或权益决定"}}
      """;
  private static final String ENGLISH_FINISHED = """
      {"reply": "The visit is eligible for direct pay under the demo group plan, with a remaining balance of 3500 MYR.",
       "done": true,
       "summary": {"direct_pay_available": true, "direct_pay_scope": "Ordinary outpatient specialist visits at network hospitals",
                   "reimbursement_required_for": "Special-needs outpatient visits and self-funded items",
                   "remaining_balance": 3500, "currency": "MYR",
                   "submission_deadline_days": 90,
                   "required_materials": ["official receipt", "itemized charges", "prescription"],
                   "source": "demo-group-plan-2024-2025",
                   "boundary": "This assistant does not make a final claim or entitlement decision"}}
      """;

  @Test void delegates_the_reply_to_the_host_model_with_a_benefits_system_prompt() {
    RecordingModel model = new RecordingModel(FINISHED);
    Msg response = new EBCBenefitsAdvisorAgent(model).call(List.of(user("福利有哪些"))).block();

    assertEquals("已查询到：门诊可直付、余额 3500 MYR、特需需报销，具体以当前计划文件为准。", response.getTextContent());
    assertEquals(List.of(MsgRole.SYSTEM, MsgRole.USER), model.messages.stream().map(Msg::getRole).toList());
    String system = model.messages.getFirst().getTextContent();
    assertTrue(system.contains("employee-benefits lookup advisor"));
    assertTrue(system.contains("company group"));
    assertTrue(system.contains("insurance plan"));
    assertTrue(system.contains("consented"));
    assertTrue(system.contains("remaining balances"));
    assertTrue(system.contains("strict JSON"));
    assertTrue(system.contains("Use one language per response"));
    assertFalse(containsHan(system));
    // The Host-registered recipient contract fields must be spelled out exactly in the instructions.
    assertTrue(system.contains("direct_pay_available"));
    assertTrue(system.contains("direct_pay_scope"));
    assertTrue(system.contains("reimbursement_required_for"));
    assertTrue(system.contains("remaining_balance"));
    assertTrue(system.contains("currency"));
    assertTrue(system.contains("submission_deadline_days"));
    assertTrue(system.contains("required_materials"));
    assertTrue(system.contains("source"));
    assertTrue(system.contains("boundary"));
    assertFalse(system.contains("benefit journey"));
    assertFalse(system.contains("AQ Health Assistant"));
    assertFalse(system.contains("Alex Coverage Advisor"));
    assertFalse(system.contains("Main Bot"));
    assertFalse(system.contains("next step"));
    assertFalse(system.contains("handoff"));
  }

  @Test void keeps_english_agent_output_free_of_unnecessary_chinese_text() {
    RecordingModel model = new RecordingModel(ENGLISH_FINISHED);
    Msg response = new EBCBenefitsAdvisorAgent(model)
        .call(List.of(user("What benefits apply to this visit?"))).block();

    assertFalse(containsHan(response.getTextContent()));
    assertFalse(containsHan(response.getMetadata().toString()));
    assertFalse(containsHan(model.messages.getFirst().getTextContent()));
  }

  @Test void keeps_the_step_open_without_presenting_an_empty_summary_when_done_is_false() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel(UNFINISHED_EMPTY_SUMMARY))
        .call(List.of(user("福利"))).block();

    assertEquals("需要先确认你的员工身份与授权，我才能查询你的福利信息。", response.getTextContent());
    // An empty summary must never be carried in the turn proposal: the Host bridge would reject an
    // empty summary object as an invalid proposal and fail the round instead of keeping it open.
    assertTrue(response.getMetadata().isEmpty());
    assertFalse(response.getMetadata().containsKey("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
  }

  @Test void keeps_the_step_open_and_attaches_a_contract_summary_without_lifecycle_when_done_is_false() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel(UNFINISHED_PARTIAL_SUMMARY))
        .call(List.of(user("福利"))).block();

    assertEquals("身份确认后即可查询福利与余额信息。", response.getTextContent());
    assertEquals(1, response.getMetadata().size());
    assertEquals(Map.of("summary", Map.of("source", "当前员工福利计划文件",
            "boundary", "身份或授权确认后才可查询")),
        response.getMetadata().get("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
  }

  @Test void declares_completion_and_attaches_the_exact_contract_summary_when_done_is_true() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel(FINISHED)).call(List.of(user("福利"))).block();

    assertTrue(response.getTextContent().contains("直付"));
    assertEquals(1, response.getMetadata().size());
    assertEquals(Map.of("lifecycle", "COMPLETED",
            "summary", Map.of("direct_pay_available", true,
                "direct_pay_scope", "门诊和常规住院",
                "reimbursement_required_for", "特需和自费项目",
                "remaining_balance", 3500, "currency", "MYR",
                "submission_deadline_days", 90,
                "required_materials", List.of("发票", "处方"),
                "source", "当前员工福利计划文件",
                "boundary", "本步骤不作最终理赔或权益决定")),
        response.getMetadata().get("oc2.turn_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.lifecycle_proposal"));
    assertFalse(response.getMetadata().containsKey("oc2.summary"));
  }

  @Test void preserves_plain_text_as_an_unfinished_reply() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel("请提供员工编号后再查询。"))
        .call(List.of(user("福利"))).block();

    assertEquals("请提供员工编号后再查询。", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_the_json_is_missing_required_fields() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel("{\"summary\": {}}"))
        .call(List.of(user("福利"))).block();

    assertEquals("I couldn't generate benefits guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_done_true_summary_misses_contract_fields() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel("""
        {"reply": "查完了", "done": true, "summary": {"direct_pay_available": true}}
        """)).call(List.of(user("福利"))).block();

    assertEquals("I couldn't generate benefits guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_done_true_summary_has_an_unknown_field() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel("""
        {"reply": "查完了", "done": true,
         "summary": {"direct_pay_available": true, "direct_pay_scope": "门诊",
                     "reimbursement_required_for": "特需", "remaining_balance": 3500,
                     "currency": "MYR", "submission_deadline_days": 90,
                     "required_materials": ["发票"], "annual_limit": "以计划文件为准",
                     "boundary": "非最终决定"}}
        """)).call(List.of(user("福利"))).block();

    assertEquals("I couldn't generate benefits guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_done_true_summary_has_a_mistyped_field() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel("""
        {"reply": "查完了", "done": true,
         "summary": {"direct_pay_available": true, "direct_pay_scope": "门诊",
                     "reimbursement_required_for": "特需", "remaining_balance": "3500",
                     "currency": "MYR", "submission_deadline_days": 90,
                     "required_materials": ["发票"], "source": "计划文件",
                     "boundary": "非最终决定"}}
        """)).call(List.of(user("福利"))).block();

    assertEquals("I couldn't generate benefits guidance just now. Please try again.", response.getTextContent());
    assertTrue(response.getMetadata().isEmpty());
  }

  @Test void returns_a_bounded_failure_message_when_done_true_required_materials_is_not_an_array() {
    Msg response = new EBCBenefitsAdvisorAgent(new RecordingModel("""
        {"reply": "查完了", "done": true,
         "summary": {"direct_pay_available": true, "direct_pay_scope": "门诊",
                     "reimbursement_required_for": "特需", "remaining_balance": 3500,
                     "currency": "MYR", "submission_deadline_days": 90,
                     "required_materials": "发票", "source": "计划文件",
                     "boundary": "非最终决定"}}
        """)).call(List.of(user("福利"))).block();

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

  private static boolean containsHan(String text) { return text != null && text.matches("(?s).*[\\u4e00-\\u9fff].*"); }

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
