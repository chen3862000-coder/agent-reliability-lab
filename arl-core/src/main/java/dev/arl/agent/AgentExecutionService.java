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

/** Agent orchestration. ReactAgent owns the reasoning loop; responses use observed tool data. */
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
    var run = new RunState(provider == null ? defaultProvider : provider, "PROTECTED".equals(mode));
    run.events().add(event("AGENT_STARTED", null, "RUNNING", null, 0));
    String error = null;
    try {
      validate(message, mode);
      var answer = agent(run).call(message);
      if (answer.hasToolCalls()) throw new ExecutionFailure("EXECUTION_LIMIT_EXCEEDED");
    } catch (ProviderException unavailable) {
      error = unavailable.code().name();
    } catch (com.alibaba.cloud.ai.graph.exception.GraphRunnerException failure) {
      error = graphError(failure);
    } catch (
        com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitExceededException
            failure) {
      error = graphError(failure);
    } catch (ExecutionFailure failure) {
      error = graphError(failure);
    } catch (java.util.concurrent.CompletionException failure) {
      error = graphError(failure);
    } catch (com.alibaba.cloud.ai.graph.agent.exception.AgentException failure) {
      error = graphError(failure);
    }
    return complete(run, error);
  }

  private void validate(String message, String mode) {
    if (message == null || message.isBlank() || message.length() > 4000)
      throw new ExecutionFailure("INVALID_REQUEST");
    if (mode != null && !mode.equals("BASELINE") && !mode.equals("PROTECTED"))
      throw new ExecutionFailure("MODE_NOT_AVAILABLE");
  }

  private com.alibaba.cloud.ai.graph.agent.ReactAgent agent(RunState run) {
    ChatModel delegate = providers.resolve(run.selected());
    AtomicInteger proposed = new AtomicInteger();
    ChatModel observed = prompt -> callModel(run, delegate, proposed, prompt);
    return new ReactAgentFactory()
        .create(observed, tools.callbacks(result -> observeTool(run, result), run.protectedMode()));
  }

  private org.springframework.ai.chat.model.ChatResponse callModel(
      RunState run,
      ChatModel delegate,
      AtomicInteger proposed,
      org.springframework.ai.chat.prompt.Prompt prompt) {
    long called = System.nanoTime();
    run.events().add(event("MODEL_CALLED", null, "RUNNING", null, 0));
    if (run.modelFailure().get() != null) throw new ExecutionFailure(run.modelFailure().get());
    boolean completed = false;
    try {
      var response = delegate.call(prompt);
      run.events().add(event("MODEL_COMPLETED", null, "SUCCESS", null, elapsed(called)));
      validateModelResponse(run, response, proposed);
      completed = true;
      return response;
    } finally {
      if (!completed) failModel(run, called);
    }
  }

  private void validateModelResponse(
      RunState run,
      org.springframework.ai.chat.model.ChatResponse response,
      AtomicInteger proposed) {
    if (response == null
        || response.getResult() == null
        || response.getResult().getOutput() == null)
      throw modelFailure(run, "INVALID_MODEL_RESPONSE");
    var output = response.getResult().getOutput();
    if (proposed.addAndGet(output.getToolCalls().size()) > 8)
      throw modelFailure(run, "EXECUTION_LIMIT_EXCEEDED");
    output.getToolCalls().forEach(call -> observeToolProposal(run, call.name()));
  }

  private void observeToolProposal(RunState run, String name) {
    boolean known = tools.contains(name);
    run.events().add(event("TOOL_PROPOSED", known ? name : "[unregistered]", "PROPOSED", null, 0));
    if (!known) throw modelFailure(run, "TOOL_NOT_FOUND");
  }

  private ExecutionFailure modelFailure(RunState run, String code) {
    run.modelFailure().set(code);
    return new ExecutionFailure(code);
  }

  private void failModel(RunState run, long called) {
    // The graph may turn exceptions into messages; preserve failure outside graph state.
    run.modelFailure().compareAndSet(null, "PROVIDER_UNAVAILABLE");
    run.events()
        .add(event("MODEL_FAILED", null, "ERROR", run.modelFailure().get(), elapsed(called)));
  }

  private static void observeTool(RunState run, ToolResult result) {
    run.results().add(result);
    run.events()
        .add(
            event(
                result.errorCode() == null ? "TOOL_EXECUTED" : "TOOL_FAILED",
                result.tool(),
                result.errorCode() == null ? "SUCCESS" : "ERROR",
                result.errorCode(),
                0));
  }

  private AgentResponse complete(RunState run, String caughtError) {
    String error = resolveError(run, caughtError);
    String status = status(error);
    run.events().add(event("AGENT_COMPLETED", null, status, error, elapsed(run.started())));
    traces.save(
        new TraceStore.Trace(
            run.id(),
            run.modelName() == null ? null : run.selected(),
            run.modelName(),
            run.events()));
    return new AgentResponse(
        status,
        message(run.results(), error),
        error,
        run.id(),
        run.modelName() == null ? null : run.selected(),
        run.modelName(),
        run.results().stream().map(ToolResult::tool).distinct().toList(),
        run.results());
  }

  private static String resolveError(RunState run, String caughtError) {
    String error = run.modelFailure().get() == null ? caughtError : run.modelFailure().get();
    if (error == null) {
      error =
          run.results().stream()
              .map(ToolResult::errorCode)
              .filter(code -> code != null)
              .findFirst()
              .orElse(null);
    }
    return error == null && run.results().isEmpty() ? "UNSUPPORTED_REQUEST" : error;
  }

  private static String graphError(Throwable failure) {
    String error = "PROVIDER_UNAVAILABLE";
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ExecutionFailure explicit) return explicit.getMessage();
      if (cause
          instanceof
          com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitExceededException)
        error = "EXECUTION_LIMIT_EXCEEDED";
    }
    return error;
  }

  private static String status(String error) {
    return error == null ? "SUCCESS" : isUnsupported(error) ? "UNSUPPORTED" : "ERROR";
  }

  private static String message(List<ToolResult> results, String error) {
    return error == null
        ? String.join(" ", results.stream().map(ToolResult::message).toList())
        : error.replace('_', ' ');
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

  private final class RunState {
    private final String id = UUID.randomUUID().toString();
    private final String selected;
    private final String modelName;
    private final boolean protectedMode;
    private final List<TraceStore.Event> events = new ArrayList<>();
    private final List<ToolResult> results = new ArrayList<>();
    private final long started = System.nanoTime();
    private final AtomicReference<String> modelFailure = new AtomicReference<>();

    RunState(String selected, boolean protectedMode) {
      this.selected = selected;
      this.protectedMode = protectedMode;
      modelName =
          providers.availableProviders().stream()
              .filter(p -> p.id().equals(selected))
              .map(p -> p.model())
              .findFirst()
              .orElse(null);
    }

    String id() {
      return id;
    }

    String selected() {
      return selected;
    }

    String modelName() {
      return modelName;
    }

    boolean protectedMode() {
      return protectedMode;
    }

    List<TraceStore.Event> events() {
      return events;
    }

    List<ToolResult> results() {
      return results;
    }

    long started() {
      return started;
    }

    AtomicReference<String> modelFailure() {
      return modelFailure;
    }
  }
}
