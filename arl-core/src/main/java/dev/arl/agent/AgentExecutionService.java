package dev.arl.agent;

import dev.arl.llm.LlmProviderRegistry;
import dev.arl.llm.ProviderException;
import dev.arl.observability.TraceStore;
import dev.arl.tools.CommerceToolRegistry;
import dev.arl.tools.ToolResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.model.ChatModel;

/**
 * M1 read-only orchestration. ReactAgent owns the reasoning loop; responses use observed tool data.
 */
public final class AgentExecutionService {
  private final LlmProviderRegistry providers;
  private final String defaultProvider;
  private final CommerceToolRegistry tools;
  private final TraceStore traces;

  public AgentExecutionService(
      LlmProviderRegistry providers,
      String defaultProvider,
      CommerceToolRegistry tools,
      TraceStore traces) {
    this.providers = providers;
    this.defaultProvider = defaultProvider;
    this.tools = tools;
    this.traces = traces;
  }

  public AgentResponse chat(String message, String provider, String mode) {
    String id = UUID.randomUUID().toString();
    String selected = provider == null ? defaultProvider : provider;
    String modelName =
        providers.availableProviders().stream()
            .filter(p -> p.id().equals(selected))
            .map(p -> p.model())
            .findFirst()
            .orElse(null);
    List<TraceStore.Event> events = new ArrayList<>();
    List<ToolResult> results = new ArrayList<>();
    long started = System.nanoTime();
    events.add(event("AGENT_STARTED", null, "RUNNING", null, 0));
    String error = null;
    AtomicReference<String> modelFailure = new AtomicReference<>();
    try {
      if (message == null || message.isBlank() || message.length() > 4000)
        throw new ExecutionFailure("INVALID_REQUEST");
      if (mode != null && !mode.equals("BASELINE"))
        throw new ExecutionFailure("MODE_NOT_AVAILABLE");
      ChatModel delegate = providers.resolve(selected);
      AtomicInteger proposed = new AtomicInteger();
      ChatModel observed =
          prompt -> {
            long called = System.nanoTime();
            events.add(event("MODEL_CALLED", null, "RUNNING", null, 0));
            if (modelFailure.get() != null) throw new ExecutionFailure(modelFailure.get());
            boolean completed = false;
            try {
              var response = delegate.call(prompt);
              events.add(event("MODEL_COMPLETED", null, "SUCCESS", null, elapsed(called)));
              if (response == null
                  || response.getResult() == null
                  || response.getResult().getOutput() == null) {
                modelFailure.set("INVALID_MODEL_RESPONSE");
                throw new ExecutionFailure(modelFailure.get());
              }
              var output = response.getResult().getOutput();
              if (proposed.addAndGet(output.getToolCalls().size()) > 8) {
                modelFailure.set("EXECUTION_LIMIT_EXCEEDED");
                throw new ExecutionFailure(modelFailure.get());
              }
              for (var call : output.getToolCalls()) {
                boolean known = tools.contains(call.name());
                events.add(
                    event(
                        "TOOL_PROPOSED",
                        known ? call.name() : "[unregistered]",
                        "PROPOSED",
                        null,
                        0));
                if (!known) {
                  modelFailure.set("TOOL_NOT_FOUND");
                  throw new ExecutionFailure(modelFailure.get());
                }
              }
              completed = true;
              return response;
            } finally {
              // The graph may turn exceptions into messages; preserve failure outside graph state.
              if (!completed) {
                modelFailure.compareAndSet(null, "PROVIDER_UNAVAILABLE");
                events.add(
                    event("MODEL_FAILED", null, "ERROR", modelFailure.get(), elapsed(called)));
              }
            }
          };
      var agent =
          new ReactAgentFactory()
              .create(
                  observed,
                  tools.callbacks(
                      result -> {
                        results.add(result);
                        events.add(
                            event(
                                result.errorCode() == null ? "TOOL_EXECUTED" : "TOOL_FAILED",
                                result.tool(),
                                result.errorCode() == null ? "SUCCESS" : "ERROR",
                                result.errorCode(),
                                0));
                      }));
      var answer = agent.call(message);
      if (answer.hasToolCalls()) throw new ExecutionFailure("EXECUTION_LIMIT_EXCEEDED");
    } catch (ProviderException unavailable) {
      error = unavailable.code().name();
    } catch (com.alibaba.cloud.ai.graph.exception.GraphRunnerException
        | com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitExceededException
        | ExecutionFailure
        | java.util.concurrent.CompletionException
        | com.alibaba.cloud.ai.graph.agent.exception.AgentException failure) {
      error = "PROVIDER_UNAVAILABLE";
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof ExecutionFailure explicit) {
          error = explicit.getMessage();
          break;
        }
        if (cause
            instanceof
            com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitExceededException) {
          error = "EXECUTION_LIMIT_EXCEEDED";
          break;
        }
      }
    }
    if (modelFailure.get() != null) error = modelFailure.get();
    if (error == null)
      error =
          results.stream()
              .map(ToolResult::errorCode)
              .filter(code -> code != null)
              .findFirst()
              .orElse(null);
    if (error == null && results.isEmpty()) error = "UNSUPPORTED_REQUEST";
    String status = error == null ? "SUCCESS" : isUnsupported(error) ? "UNSUPPORTED" : "ERROR";
    String text =
        error == null
            ? String.join(" ", results.stream().map(ToolResult::message).toList())
            : error.replace('_', ' ');
    events.add(event("AGENT_COMPLETED", null, status, error, elapsed(started)));
    traces.save(new TraceStore.Trace(id, modelName == null ? null : selected, modelName, events));
    return new AgentResponse(
        status,
        text,
        error,
        id,
        modelName == null ? null : selected,
        modelName,
        results.stream().map(ToolResult::tool).distinct().toList(),
        results);
  }

  private static TraceStore.Event event(
      String type, String tool, String status, String error, long duration) {
    return new TraceStore.Event(Instant.now(), type, tool, status, error, duration);
  }

  private static long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  private static final class ExecutionFailure extends RuntimeException {
    ExecutionFailure(String code) {
      super(code);
    }
  }

  private static boolean isUnsupported(String code) {
    return code.equals("UNSUPPORTED_REQUEST") || code.equals("RESERVATION_NOT_ENABLED");
  }
}
