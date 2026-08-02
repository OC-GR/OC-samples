package com.oc2.itsupport;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Deterministic synthetic IT support guide with no system integration. */
public final class ItSupportGuideAgent implements Agent {
  private static final String NO_MATCH = "This demo IT Support Guide covers only sign-in, VPN, device setup, and approved software questions. Please contact your IT support team for other help.";
  private final AtomicBoolean interrupted = new AtomicBoolean();

  public ItSupportGuideAgent(Model hostModel) { }

  @Override public String getAgentId() { return "it-support-guide"; }
  @Override public String getName() { return "IT Support Guide"; }
  @Override public void interrupt() { interrupted.set(true); }
  @Override public void interrupt(Msg message) { interrupt(); }
  @Override public Mono<Void> observe(Msg message) { return Mono.empty(); }
  @Override public Mono<Void> observe(List<Msg> messages) { return Mono.empty(); }
  @Override public Mono<Msg> call(List<Msg> messages) { return Mono.just(result(messages)); }
  @Override public Mono<Msg> call(List<Msg> messages, Class<?> responseFormat) { return call(messages); }
  @Override public Mono<Msg> call(List<Msg> messages, com.fasterxml.jackson.databind.JsonNode options) { return call(messages); }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options) {
    return Flux.just(new Event(EventType.AGENT_RESULT, result(messages), true));
  }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options, Class<?> responseFormat) { return stream(messages, options); }
  @Override public Flux<Event> stream(List<Msg> messages, StreamOptions options, com.fasterxml.jackson.databind.JsonNode outputSchema) { return stream(messages, options); }

  private Msg result(List<Msg> messages) {
    if (interrupted.getAndSet(false)) return message("", GenerateReason.INTERRUPTED);
    String input = messages == null ? "" : messages.stream().filter(message -> message.getRole() == MsgRole.USER)
        .reduce((first, second) -> second).map(Msg::getTextContent).orElse("").toLowerCase(Locale.ROOT);
    return message(answer(input), GenerateReason.MODEL_STOP);
  }

  private static String answer(String input) {
    if (contains(input, "vpn", "virtual private network", "远程访问")) return "For this demo, verify that your approved VPN client is installed, sign in with your existing organization account, and follow your IT team's documented connection steps. This guide cannot inspect or change your network connection.";
    if (contains(input, "sign in", "login", "password", "账号", "登录", "密码")) return "For this demo, use the approved sign-in page and your existing organization account. If you cannot sign in, contact your IT support team; this guide cannot reset passwords or access accounts.";
    if (contains(input, "device", "laptop", "setup", "设备", "电脑", "设置")) return "For this demo, follow your organization's documented device setup checklist and use only approved configuration steps. This guide cannot inspect, enroll, or change a device.";
    if (contains(input, "software", "install", "application", "软件", "安装", "应用")) return "For this demo, request or install software only through your organization's approved catalog and follow the published instructions. This guide cannot install software or grant access.";
    return NO_MATCH;
  }

  private static boolean contains(String input, String... terms) {
    for (String term : terms) if (input.contains(term)) return true;
    return false;
  }

  private static Msg message(String text, GenerateReason reason) {
    return Msg.builder().role(MsgRole.ASSISTANT).textContent(text).generateReason(reason).build();
  }
}
