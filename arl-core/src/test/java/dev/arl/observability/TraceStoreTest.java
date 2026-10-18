package dev.arl.observability;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class TraceStoreTest {
  @Test
  void completedSnapshotsAreBoundedAndImmutable() {
    assertThrows(IllegalArgumentException.class, () -> new TraceStore(0));
    var store = new TraceStore(2);
    var events = new ArrayList<TraceStore.Event>();
    events.add(new TraceStore.Event(Instant.now(), "AGENT_COMPLETED", null, "SUCCESS", null, 1));
    store.save(new TraceStore.Trace("1", "deepseek", "model", events));
    events.clear();
    assertEquals(1, store.find("1").orElseThrow().events().size());
    assertThrows(
        UnsupportedOperationException.class, () -> store.find("1").orElseThrow().events().clear());
    store.save(new TraceStore.Trace("2", "grok", "model", events));
    store.save(new TraceStore.Trace("3", "grok", "model", events));
    assertTrue(store.find("1").isEmpty());
    assertTrue(store.find("2").isPresent());
    assertTrue(store.find("3").isPresent());
  }
}
