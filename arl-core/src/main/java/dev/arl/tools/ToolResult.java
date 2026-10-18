package dev.arl.tools;

public record ToolResult(String tool, String message, Object data, String errorCode) {
  public static ToolResult error(String tool, String code) {
    return new ToolResult(tool, code.replace('_', ' '), null, code);
  }
}
