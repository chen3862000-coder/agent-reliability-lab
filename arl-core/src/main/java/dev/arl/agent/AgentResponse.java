package dev.arl.agent;

import dev.arl.tools.ToolResult;
import java.util.List;

public record AgentResponse(
    String status,
    String message,
    String errorCode,
    String traceId,
    String provider,
    String model,
    List<String> toolCalls,
    List<ToolResult> results) {
  public AgentResponse {
    toolCalls = List.copyOf(toolCalls);
    results = List.copyOf(results);
  }
}
