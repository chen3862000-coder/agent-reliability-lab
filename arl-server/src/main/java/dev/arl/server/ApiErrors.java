package dev.arl.server;

import dev.arl.agent.AgentResponse;
import dev.arl.observability.TraceStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiErrors {
  private final TraceStore traces;

  public ApiErrors(TraceStore traces) {
    this.traces = traces;
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<AgentResponse> invalidRequest() {
    return error(400, "INVALID_REQUEST");
  }

  @ExceptionHandler(AgentController.TraceNotFoundException.class)
  public ResponseEntity<AgentResponse> traceNotFound() {
    return error(404, "TRACE_NOT_FOUND");
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<AgentResponse> endpointNotFound() {
    return error(404, "ENDPOINT_NOT_FOUND");
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<AgentResponse> methodNotAllowed() {
    return error(405, "METHOD_NOT_ALLOWED");
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<AgentResponse> mediaTypeNotSupported() {
    return error(415, "UNSUPPORTED_MEDIA_TYPE");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<AgentResponse> unexpected() {
    return error(500, "INTERNAL_ERROR");
  }

  private ResponseEntity<AgentResponse> error(int status, String code) {
    String id = UUID.randomUUID().toString();
    traces.save(
        new TraceStore.Trace(
            id,
            null,
            null,
            List.of(
                new TraceStore.Event(Instant.now(), "REQUEST_REJECTED", null, "ERROR", code, 0))));
    return ResponseEntity.status(status)
        .body(
            new AgentResponse(
                "ERROR", code.replace('_', ' '), code, id, null, null, List.of(), List.of()));
  }
}
