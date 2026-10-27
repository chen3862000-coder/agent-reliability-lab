package dev.arl.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * Creates a bounded Alibaba {@code ReactAgent} graph with no shared conversation/checkpoint state.
 */
public final class ReactAgentFactory {
  /**
   * Creates a graph with serial tool execution, recursion limit 64, and a four-model-call limit.
   * The returned graph and hook state must be used for one invocation only.
   */
  public ReactAgent create(ChatModel model, ToolCallback... tools) {
    return ReactAgent.builder()
        .name("arl_commerce")
        .systemPrompt(
            "You are a synthetic commerce assistant. Use registered tools for all commerce facts. "
                + "Never invent tools, tracking, records or execution results. Reservations are proposals only; "
                + "stock changes only after a separate backend confirmation. If a tool reports an error, acknowledge it. "
                + "Do not repeat failed operations. For unsupported requests, say unsupported.")
        .parallelToolExecution(false)
        .model(model)
        .tools(tools)
        .compileConfig(CompileConfig.builder().recursionLimit(64).build())
        .hooks(
            ModelCallLimitHook.builder()
                .runLimit(4)
                .exitBehavior(ModelCallLimitHook.ExitBehavior.ERROR)
                .build())
        .build();
  }
}
