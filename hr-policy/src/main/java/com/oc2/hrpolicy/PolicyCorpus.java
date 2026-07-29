package com.oc2.hrpolicy;

/** Versioned, curated policy text packaged with release 1.0.0. */
final class PolicyCorpus {
  static final String NO_MATCH = "This question is not covered by the approved HR policy guidance. Please contact HR for help.";

  private PolicyCorpus() { }

  static String answerFor(String message) {
    if (containsAny(message, "annual leave", "vacation", "年假", "休假")) {
      return "Annual leave requests must be submitted through the approved HR process before the requested absence. Contact HR for your current balance and local eligibility.";
    }
    if (containsAny(message, "sick leave", "病假")) {
      return "For sick leave, notify your manager as soon as practicable and follow the approved HR absence process. Contact HR for documentation requirements.";
    }
    if (containsAny(message, "parental leave", "maternity leave", "paternity leave", "育儿假", "产假", "陪产假")) {
      return "Parental leave eligibility and timing are governed by the approved HR process. Contact HR before making arrangements so the applicable policy can be confirmed.";
    }
    if (containsAny(message, "expense", "reimbursement", "报销")) {
      return "Expense and reimbursement questions are not covered by this HR policy guidance Pack. Please use the approved finance process.";
    }
    return null;
  }

  private static boolean containsAny(String message, String... terms) {
    for (String term : terms) if (message.contains(term)) return true;
    return false;
  }
}
