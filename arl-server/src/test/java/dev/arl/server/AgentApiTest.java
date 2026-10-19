package dev.arl.server;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.arl.llm.*;
import dev.arl.observability.TraceStore;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    classes = {ArlApplication.class, AgentApiTest.OfflineProviders.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AgentApiTest {
  @Value("${local.server.port}")
  int port;

  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient client = HttpClient.newHttpClient();

  private HttpResponse<String> post(String body) throws Exception {
    return post("/agent/chat", body);
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void chatReadsProductInventoryAndOrderOverHttp() throws Exception {
    for (String kind : List.of("product", "inventory", "order")) {
      var http =
          post("{\"message\":\"" + kind + "\",\"provider\":\"deepseek\",\"mode\":\"BASELINE\"}");
      assertEquals(200, http.statusCode(), http.body());
      var response = JSON.readTree(http.body());
      assertEquals("SUCCESS", response.path("status").asText());
      assertEquals("deepseek", response.path("provider").asText());
      assertEquals("get_" + kind, response.path("toolCalls").get(0).asText());
      assertTrue(response.path("results").get(0).path("data").isObject());
      var trace = get("/traces/" + response.path("traceId").asText());
      assertEquals(200, trace.statusCode());
      assertTrue(trace.body().contains("TOOL_EXECUTED"));
      assertFalse(trace.body().contains("fabricated"));
    }
    assertTrue(post("{\"message\":\"inventory\"}").body().contains("20 units"));
  }

  @Test
  void invalidApiRequestsAreRejectedWithQueryableErrorTraces() throws Exception {
    for (String body :
        List.of(
            "{}",
            "null",
            "bad-json",
            "{\"message\":\" \"}",
            "{\"message\":\"inventory\",\"extra\":true}",
            "{\"message\":\"" + "x".repeat(4001) + "\"}")) {
      var response = post(body);
      assertEquals(400, response.statusCode(), response.body());
      var error = JSON.readTree(response.body());
      assertEquals("INVALID_REQUEST", error.path("errorCode").asText());
      assertEquals(200, get("/traces/" + error.path("traceId").asText()).statusCode());
      assertFalse(response.body().contains("stackTrace"));
    }
  }

  @Test
  void providerModeAndToolErrorsUseConsistentStatuses() throws Exception {
    assertError(
        post("{\"message\":\"inventory\",\"provider\":\"other\"}"), 400, "UNKNOWN_PROVIDER");
    assertError(
        post("{\"message\":\"inventory\",\"provider\":\"grok\"}"), 503, "PROVIDER_NOT_CONFIGURED");
    assertError(post("{\"message\":\"inventory\",\"mode\":\"OTHER\"}"), 400, "MODE_NOT_AVAILABLE");
    assertError(post("{\"message\":\"fail\"}"), 502, "PROVIDER_UNAVAILABLE");
    assertError(post("{\"message\":\"missing\"}"), 422, "NOT_FOUND");
    assertError(post("{\"message\":\"invalid\"}"), 422, "INVALID_TOOL_ARGUMENTS");
    assertError(post("{\"message\":\"unsupported\"}"), 422, "UNSUPPORTED_REQUEST");
    assertError(post("{\"message\":\"reserve\"}"), 422, "RESERVATION_NOT_ENABLED");
    assertTrue(post("{\"message\":\"inventory\"}").body().contains("20 units"));
  }

  @Test
  void protectedReservationRequiresConfirmationAndIsIdempotent() throws Exception {
    var proposal = post("{\"message\":\"reserve\",\"mode\":\"PROTECTED\"}");
    assertEquals(200, proposal.statusCode(), proposal.body());
    var proposalJson = JSON.readTree(proposal.body());
    var confirmationId =
        proposalJson.path("results").get(0).path("data").path("confirmationId").asText();
    assertTrue(post("{\"message\":\"inventory\"}").body().contains("20 units"));

    var confirmBody =
        "{\"confirmationId\":\"" + confirmationId + "\",\"idempotencyKey\":\"idem-http-1\"}";
    var committed = post("/agent/confirm", confirmBody);
    assertEquals(200, committed.statusCode(), committed.body());
    assertEquals(17, JSON.readTree(committed.body()).path("remaining").asInt());
    assertTrue(post("{\"message\":\"inventory\"}").body().contains("17 units"));

    var replay = post("/agent/confirm", confirmBody);
    assertEquals(200, replay.statusCode(), replay.body());
    assertEquals(17, JSON.readTree(replay.body()).path("remaining").asInt());
  }

  private static void assertError(HttpResponse<String> response, int status, String code)
      throws Exception {
    assertEquals(status, response.statusCode(), response.body());
    assertEquals(code, JSON.readTree(response.body()).path("errorCode").asText());
    assertFalse(response.body().contains("secret-provider-detail"));
  }

  @Test
  void healthProviderStatusAndRoutingAreAvailableWithoutKeys() throws Exception {
    assertEquals("UP", JSON.readTree(get("/health").body()).path("status").asText());
    assertEquals(2, JSON.readTree(get("/providers").body()).size());
    assertError(get("/traces/not-present"), 404, "TRACE_NOT_FOUND");
    assertError(get("/agent/confirm"), 405, "METHOD_NOT_ALLOWED");
    assertError(get("/agent/chat"), 405, "METHOD_NOT_ALLOWED");
    var media =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/agent/chat"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("hello"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertError(media, 415, "UNSUPPORTED_MEDIA_TYPE");
    var unexpected = new ApiErrors(new TraceStore(2)).unexpected();
    assertEquals(500, unexpected.getStatusCode().value());
    assertEquals("INTERNAL_ERROR", unexpected.getBody().errorCode());
    assertEquals(502, AgentController.status("INVALID_MODEL_RESPONSE"));
  }

  @TestConfiguration
  static class OfflineProviders {
    @Bean
    @Primary
    LlmProviderRegistry offlineRegistry() {
      return new LlmProviderRegistry() {
        public ChatModel resolve(String id) {
          if (id.equals("grok"))
            throw new ProviderException(ProviderException.Code.PROVIDER_NOT_CONFIGURED);
          if (!id.equals("deepseek"))
            throw new ProviderException(ProviderException.Code.UNKNOWN_PROVIDER);
          return OfflineProviders::scriptedResponse;
        }

        public List<LlmProviderInfo> availableProviders() {
          return new ProviderRegistry(Map.of()).availableProviders();
        }
      };
    }

    private static ChatResponse scriptedResponse(org.springframework.ai.chat.prompt.Prompt prompt) {
      String question = question(prompt);
      if (question.equals("fail")) throw new IllegalStateException("secret-provider-detail");
      if (question.equals("unsupported") || hasToolResponse(prompt)) return fabricated();
      return toolCall(tool(question), arguments(question));
    }

    private static String question(org.springframework.ai.chat.prompt.Prompt prompt) {
      return prompt.getInstructions().stream()
          .filter(m -> m instanceof UserMessage)
          .findFirst()
          .orElseThrow()
          .getText();
    }

    private static boolean hasToolResponse(org.springframework.ai.chat.prompt.Prompt prompt) {
      return prompt.getInstructions().stream().anyMatch(m -> m instanceof ToolResponseMessage);
    }

    private static ChatResponse fabricated() {
      return new ChatResponse(
          List.of(new Generation(new AssistantMessage("fabricated answer ignored"))));
    }

    private static String tool(String question) {
      return switch (question) {
        case "product" -> "get_product";
        case "order" -> "get_order";
        case "reserve" -> "reserve_inventory";
        default -> "get_inventory";
      };
    }

    private static String arguments(String question) {
      return switch (question) {
        case "order" -> "{\"orderId\":\"123\"}";
        case "reserve" -> "{\"sku\":\"SKU-1001\",\"quantity\":3}";
        case "missing" -> "{\"sku\":\"MISSING\"}";
        case "invalid" -> "{}";
        default -> "{\"sku\":\"SKU-1001\"}";
      };
    }

    private static ChatResponse toolCall(String tool, String arguments) {
      return new ChatResponse(
          List.of(
              new Generation(
                  AssistantMessage.builder()
                      .content("")
                      .toolCalls(
                          List.of(
                              new AssistantMessage.ToolCall(
                                  "api-tool", "function", tool, arguments)))
                      .build())));
    }
  }
}
