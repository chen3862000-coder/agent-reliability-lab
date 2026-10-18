package dev.arl.llm;

public final class ProviderException extends RuntimeException {
  public enum Code {
    UNKNOWN_PROVIDER,
    PROVIDER_NOT_CONFIGURED
  }

  private final Code code;

  public ProviderException(Code code) {
    super(code.name());
    this.code = code;
  }

  public Code code() {
    return code;
  }
}
