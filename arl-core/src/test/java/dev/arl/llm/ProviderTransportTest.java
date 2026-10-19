package dev.arl.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.arl.agent.ReactAgentFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ProviderTransportTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = {"deepseek", "grok"})
  void adapterCompletesActualHttpToolRoundTrip(String provider) throws Exception {
    var bodies = new CopyOnWriteArrayList<String>();
    var paths = new CopyOnWriteArrayList<String>();
    var server = toolRoundTripServer(bodies, paths);
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      var registry =
          new ProviderRegistry(
              Map.of(
                  "DEEPSEEK_API_KEY", "fake", "XAI_API_KEY", "fake", "ARL_LLM_TEMPERATURE", "0.3"),
              Map.of("deepseek", url, "grok", url));
      var executions = new AtomicInteger();
      ToolCallback tool =
          new ToolCallback() {
            public ToolDefinition getToolDefinition() {
              return ToolDefinition.builder()
                  .name("probe")
                  .description("Synthetic marker")
                  .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                  .build();
            }

            public String call(String input) {
              executions.incrementAndGet();
              return "ARL_M0_OK";
            }
          };
      assertEquals(
          "ARL_M0_OK",
          new ReactAgentFactory()
              .create(registry.resolve(provider), tool)
              .call("Use probe")
              .getText());
      assertEquals(1, executions.get());
      assertProviderRequests(provider, bodies, paths);
      assertTrue(bodies.getLast().contains("ARL_M0_OK"));
    } finally {
      server.stop(0);
    }
  }

  private static HttpServer toolRoundTripServer(List<String> bodies, List<String> paths)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          paths.add(exchange.getRequestURI().getPath());
          byte[] response = toolRoundTripResponse(bodies.size());
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    return server;
  }

  private static byte[] toolRoundTripResponse(int requestCount) {
    String message =
        requestCount == 1
            ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"probe\",\"arguments\":\"{}\"}}]}"
            : "{\"role\":\"assistant\",\"content\":\"ARL_M0_OK\"}";
    return ("{\"id\":\"mock\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"mock\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":"
            + message
            + "}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static void assertProviderRequests(
      String provider, List<String> bodies, List<String> paths) throws Exception {
    assertEquals(2, bodies.size());
    assertTrue(paths.stream().allMatch(p -> p.equals(path(provider))));
    var first = JSON.readTree(bodies.getFirst());
    assertEquals(
        provider.equals("deepseek") ? "deepseek-flash" : "grok-4.7", first.path("model").asText());
    if (provider.equals("deepseek"))
      assertEquals("disabled", first.path("thinking").path("type").asText(), bodies.getFirst());
    for (String body : bodies) assertProviderBody(provider, body);
  }

  private static String path(String provider) {
    return provider.equals("deepseek") ? "/chat/completions" : "/v1/chat/completions";
  }

  private static void assertProviderBody(String provider, String body) throws Exception {
    var sent = JSON.readTree(body);
    assertEquals(1024, sent.path("max_tokens").asInt());
    if (provider.equals("deepseek")) {
      assertEquals("disabled", sent.path("thinking").path("type").asText());
      assertEquals(0.3, sent.path("temperature").asDouble());
    } else {
      assertFalse(sent.has("thinking"));
      assertFalse(sent.has("temperature"), "DeepSeek options must not leak to Grok");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"deepseek", "grok"})
  void configuredReadTimeoutTerminatesRequestWithoutRetry(String provider) throws Exception {
    var count = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/",
        e -> {
          count.incrementAndGet();
          e.getRequestBody().readAllBytes();
          try {
            Thread.sleep(4000);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          e.close();
        });
    server.start();
    try {
      var registry =
          new ProviderRegistry(
              Map.of(
                  provider.equals("deepseek") ? "DEEPSEEK_API_KEY" : "XAI_API_KEY",
                  "fake",
                  "ARL_LLM_TIMEOUT_SECONDS",
                  "1"),
              Map.of(provider, "http://127.0.0.1:" + server.getAddress().getPort()));
      assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(3),
          () -> {
            var error =
                assertThrows(Exception.class, () -> registry.resolve(provider).call("probe"));
            assertTrue(
                hasCause(error, java.net.http.HttpTimeoutException.class),
                "The request must fail because of its configured timeout");
          });
      assertEquals(1, count.get());
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"deepseek", "grok"})
  void providerFailureDoesNotRetry(String provider) throws Exception {
    var count = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        e -> {
          count.incrementAndGet();
          e.getRequestBody().readAllBytes();
          e.sendResponseHeaders(503, -1);
          e.close();
        });
    server.start();
    try {
      var registry =
          new ProviderRegistry(
              Map.of(provider.equals("deepseek") ? "DEEPSEEK_API_KEY" : "XAI_API_KEY", "fake"),
              Map.of(provider, "http://127.0.0.1:" + server.getAddress().getPort()));
      assertThrows(Exception.class, () -> registry.resolve(provider).call("probe"));
      assertEquals(1, count.get());
    } finally {
      server.stop(0);
    }
  }

  private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (type.isInstance(cause)) return true;
    }
    return false;
  }
}
