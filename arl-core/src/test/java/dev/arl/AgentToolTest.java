package dev.arl;

import static org.junit.jupiter.api.Assertions.*;

import dev.arl.agent.ReactAgentFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class AgentToolTest {
  static ToolCallback probe(AtomicInteger count) {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("compatibility_probe")
            .description("Read a synthetic compatibility marker")
            .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
            .build();
      }

      public String call(String input) {
        count.incrementAndGet();
        return "{\"marker\":\"ARL_M0_OK\"}";
      }
    };
  }

  @Test
  void frameworkExecutesToolAndReturnsResult() throws Exception {
    var calls = new AtomicInteger();
    var executions = new AtomicInteger();
    ChatModel model =
        prompt -> {
          if (calls.getAndIncrement() == 0)
            return new ChatResponse(
                List.of(
                    new Generation(
                        AssistantMessage.builder()
                            .content("")
                            .toolCalls(
                                List.of(
                                    new AssistantMessage.ToolCall(
                                        "probe-1", "function", "compatibility_probe", "{}")))
                            .build())));
          assertTrue(
              prompt.getInstructions().stream()
                  .filter(m -> m instanceof ToolResponseMessage)
                  .map(m -> (ToolResponseMessage) m)
                  .flatMap(m -> m.getResponses().stream())
                  .anyMatch(r -> r.responseData().contains("ARL_M0_OK")));
          return new ChatResponse(List.of(new Generation(new AssistantMessage("ARL_M0_OK"))));
        };
    assertEquals(
        "ARL_M0_OK",
        new ReactAgentFactory().create(model, probe(executions)).call("Run the probe").getText());
    assertEquals(2, calls.get());
    assertEquals(1, executions.get());
  }

  @Test
  void repeatedToolSelectionIsBounded() {
    var calls = new AtomicInteger();
    var executions = new AtomicInteger();
    ChatModel looping =
        prompt ->
            new ChatResponse(
                List.of(
                    new Generation(
                        AssistantMessage.builder()
                            .content("")
                            .toolCalls(
                                List.of(
                                    new AssistantMessage.ToolCall(
                                        "p" + calls.incrementAndGet(),
                                        "function",
                                        "compatibility_probe",
                                        "{}")))
                            .build())));
    assertThrows(
        Exception.class,
        () -> new ReactAgentFactory().create(looping, probe(executions)).call("loop"));
    assertEquals(4, calls.get());
    assertEquals(4, executions.get());
  }

  @Test
  void concurrentAgentsHaveIndependentHistoriesAndBudgets() throws Exception {
    var calls = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
    var executions = new AtomicInteger();
    ChatModel sharedModel =
        prompt -> {
          var users =
              prompt.getInstructions().stream().filter(m -> m instanceof UserMessage).toList();
          assertEquals(1, users.size(), "Another request's history leaked into this graph");
          String marker = users.getFirst().getText();
          int turn =
              calls.computeIfAbsent(marker, ignored -> new AtomicInteger()).incrementAndGet();
          if (turn <= 2)
            return new ChatResponse(
                List.of(
                    new Generation(
                        AssistantMessage.builder()
                            .content("")
                            .toolCalls(
                                List.of(
                                    new AssistantMessage.ToolCall(
                                        marker + turn, "function", "compatibility_probe", "{}")))
                            .build())));
          return new ChatResponse(List.of(new Generation(new AssistantMessage(marker))));
        };
    var factory = new ReactAgentFactory();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
      for (int i = 0; i < 8; i++) {
        String marker = "request-" + i;
        futures.add(
            pool.submit(
                () -> factory.create(sharedModel, probe(executions)).call(marker).getText()));
      }
      for (int i = 0; i < futures.size(); i++)
        assertEquals("request-" + i, futures.get(i).get(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertEquals(8, calls.size());
    assertTrue(calls.values().stream().allMatch(n -> n.get() == 3));
    assertEquals(16, executions.get());
  }
}
