package dev.arl.agent;

import static org.junit.jupiter.api.Assertions.*;

import dev.arl.domain.CommerceCatalog;
import dev.arl.llm.*;
import dev.arl.observability.TraceStore;
import dev.arl.tools.CommerceToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;

class AgentExecutionServiceTest {
  private final TraceStore traces = new TraceStore(256);

  private AgentExecutionService service(ChatModel model) {
    LlmProviderRegistry registry =
        new LlmProviderRegistry() {
          public ChatModel resolve(String id) {
            if (!List.of("deepseek", "grok").contains(id))
              throw new ProviderException(ProviderException.Code.UNKNOWN_PROVIDER);
            return model;
          }

          public List<LlmProviderInfo> availableProviders() {
            return new ProviderRegistry(Map.of()).availableProviders();
          }
        };
    return new AgentExecutionService(
        registry, "deepseek", new CommerceToolRegistry(new CommerceCatalog()), traces);
  }

  private static ChatResponse reply(AssistantMessage message) {
    return new ChatResponse(List.of(new Generation(message)));
  }

  private static ChatModel oneTool(String tool, String arguments) {
    return prompt -> {
      boolean hasResult =
          prompt.getInstructions().stream().anyMatch(m -> m instanceof ToolResponseMessage);
      return reply(
          hasResult
              ? new AssistantMessage("Fabricated claim: 999999 units, delivered and reserved")
              : AssistantMessage.builder()
                  .content("")
                  .toolCalls(
                      List.of(new AssistantMessage.ToolCall("id", "function", tool, arguments)))
                  .build());
    };
  }

  @ParameterizedTest
  @CsvSource({
    "get_product,sku,SKU-1001,Mechanical Keyboard",
    "get_inventory,sku,SKU-1001,20 units",
    "get_order,orderId,123,PROCESSING"
  })
  void realAgentReadsCatalogAndOnlyReturnsObservedFacts(
      String tool, String key, String value, String expected) {
    var response =
        service(oneTool(tool, "{\"" + key + "\":\"" + value + "\"}"))
            .chat("private user text", "grok", "BASELINE");
    assertEquals("SUCCESS", response.status());
    assertEquals("grok", response.provider());
    assertEquals("grok-4.7", response.model());
    assertTrue(response.message().contains(expected));
    assertFalse(response.message().contains("999999"));
    assertEquals(List.of(tool), response.toolCalls());
    var trace = traces.find(response.traceId()).orElseThrow();
    assertTrue(trace.events().stream().anyMatch(e -> e.type().equals("TOOL_EXECUTED")));
    assertTrue(trace.events().stream().anyMatch(e -> e.type().equals("MODEL_CALLED")));
    assertEquals("AGENT_STARTED", trace.events().getFirst().type());
    assertEquals("AGENT_COMPLETED", trace.events().getLast().type());
    assertFalse(trace.toString().contains("private user text"));
  }

  @Test
  void unsupportedAndUnknownToolsCannotInventResults() {
    var noTools =
        service(prompt -> reply(new AssistantMessage("Your DHL parcel is delivered")))
            .chat("DHL tracking", null, null);
    assertEquals("UNSUPPORTED", noTools.status());
    assertFalse(noTools.message().contains("delivered"));
    var unknown =
        service(oneTool("secret-unregistered-tool", "{}")).chat("DHL tracking", null, null);
    assertEquals("TOOL_NOT_FOUND", unknown.errorCode());
    assertTrue(unknown.results().isEmpty());
    assertFalse(
        traces
            .find(unknown.traceId())
            .orElseThrow()
            .toString()
            .contains("secret-unregistered-tool"));
  }

  @ParameterizedTest
  @CsvSource({
    "get_inventory,{},INVALID_TOOL_ARGUMENTS",
    "get_inventory,{\"sku\":\"MISSING\"},NOT_FOUND",
    "reserve_inventory,{\"sku\":\"SKU-1001\";\"quantity\":3},RESERVATION_NOT_ENABLED"
  })
  void toolErrorsRemainErrorsEvenWhenModelClaimsSuccess(
      String tool, String arguments, String expected) {
    var response = service(oneTool(tool, arguments.replace(';', ','))).chat("query", null, null);
    assertEquals(expected, response.errorCode());
    assertNotEquals("SUCCESS", response.status());
    assertTrue(
        traces.find(response.traceId()).orElseThrow().events().stream()
            .anyMatch(e -> e.type().equals("TOOL_FAILED")));
  }

  @Test
  void providerFailuresAndLimitsAreExplicitAndSanitized() {
    var failed =
        service(
                prompt -> {
                  throw new IllegalStateException("secret-key-do-not-return");
                })
            .chat("query", null, null);
    assertEquals("PROVIDER_UNAVAILABLE", failed.errorCode());
    assertFalse(failed.toString().contains("secret-key"));
    assertEquals(
        "INVALID_MODEL_RESPONSE",
        service(prompt -> new ChatResponse(List.of())).chat("query", null, null).errorCode());
    ChatModel loops =
        prompt ->
            reply(
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                "repeat", "function", "get_inventory", "{\"sku\":\"SKU-1001\"}")))
                    .build());
    assertEquals("EXECUTION_LIMIT_EXCEEDED", service(loops).chat("query", null, null).errorCode());
    ChatModel tooMany =
        prompt ->
            reply(
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        java.util.stream.IntStream.range(0, 9)
                            .mapToObj(
                                i ->
                                    new AssistantMessage.ToolCall(
                                        "t" + i, "function", "get_inventory", "{}"))
                            .toList())
                    .build());
    assertEquals(
        "EXECUTION_LIMIT_EXCEEDED", service(tooMany).chat("query", null, null).errorCode());
  }

  @Test
  void invalidRequestsAndUnavailableProvidersDoNotCallModels() {
    var count = new AtomicInteger();
    var service =
        service(
            prompt -> {
              count.incrementAndGet();
              return reply(new AssistantMessage("unused"));
            });
    for (String message : new String[] {null, " ", "x".repeat(4001)})
      assertEquals("INVALID_REQUEST", service.chat(message, null, null).errorCode());
    assertEquals("MODE_NOT_AVAILABLE", service.chat("query", null, "PROTECTED").errorCode());
    assertEquals("UNKNOWN_PROVIDER", service.chat("query", "other", null).errorCode());
    assertEquals(0, count.get());
    var missing =
        new AgentExecutionService(
            new ProviderRegistry(Map.of()),
            "deepseek",
            new CommerceToolRegistry(new CommerceCatalog()),
            traces);
    assertEquals("PROVIDER_NOT_CONFIGURED", missing.chat("query", null, null).errorCode());
  }

  @Test
  void concurrentRequestsKeepTheirResultsAndTracesSeparate() throws Exception {
    var service = service(oneTool("get_inventory", "{\"sku\":\"SKU-1001\"}"));
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var tasks =
          java.util.stream.IntStream.range(0, 8)
              .<java.util.concurrent.Callable<AgentResponse>>mapToObj(
                  i -> () -> service.chat("request-" + i, i % 2 == 0 ? "deepseek" : "grok", null))
              .toList();
      var ids = new java.util.HashSet<String>();
      for (var future : pool.invokeAll(tasks)) {
        var response = future.get();
        assertEquals("SUCCESS", response.status());
        assertEquals(1, response.results().size());
        assertTrue(ids.add(response.traceId()));
        assertEquals(response.provider(), traces.find(response.traceId()).orElseThrow().provider());
      }
    }
  }
}
