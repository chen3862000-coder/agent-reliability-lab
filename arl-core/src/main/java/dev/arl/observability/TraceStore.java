package dev.arl.observability;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded completed-trace snapshots, in memory only. Contains no prompts, arguments or credentials.
 */
public final class TraceStore {
  public record Event(
      Instant timestamp,
      String type,
      String tool,
      String status,
      String errorCode,
      long durationMillis) {}

  public record Trace(String traceId, String provider, String model, List<Event> events) {
    public Trace {
      events = List.copyOf(events);
    }
  }

  private final int capacity;
  private final Map<String, Trace> traces = new LinkedHashMap<>();

  public TraceStore(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("Trace capacity must be positive");
    this.capacity = capacity;
  }

  public synchronized void save(Trace trace) {
    traces.put(trace.traceId(), trace);
    while (traces.size() > capacity) traces.remove(traces.keySet().iterator().next());
  }

  public synchronized Optional<Trace> find(String id) {
    return Optional.ofNullable(traces.get(id));
  }
}
