package dev.arl;

import static org.junit.jupiter.api.Assertions.*;

import dev.arl.agent.ReactAgentFactory;
import dev.arl.llm.ProviderRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in: may incur provider charges. No credentials are logged. */
@org.junit.jupiter.api.Timeout(120)
@EnabledIfSystemProperty(named = "arl.live", matches = "true")
class LiveProviderTest {
  @Test
  @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
  void deepseekToolCalling() throws Exception {
    verify("deepseek");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "XAI_API_KEY", matches = ".+")
  void grokToolCalling() throws Exception {
    verify("grok");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
  void deepseekCommerceRead() {
    verifyCommerce("deepseek");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "XAI_API_KEY", matches = ".+")
  void grokCommerceRead() {
    verifyCommerce("grok");
  }

  private void verifyCommerce(String provider) {
    var traces = new dev.arl.observability.TraceStore(4);
    var service =
        new dev.arl.agent.AgentExecutionService(
            new ProviderRegistry(System.getenv()), provider,
            new dev.arl.tools.CommerceToolRegistry(new dev.arl.domain.CommerceCatalog()), traces);
    var result =
        service.chat(
            "Use get_inventory to check available units for SKU-1001.", provider, "BASELINE");
    assertEquals("SUCCESS", result.status());
    assertTrue(result.toolCalls().contains("get_inventory"));
    assertTrue(
        result.results().stream()
            .anyMatch(
                r ->
                    r.data() instanceof dev.arl.domain.CommerceCatalog.Inventory inventory
                        && inventory.sku().equals("SKU-1001")
                        && inventory.available() == 20));
    assertTrue(traces.find(result.traceId()).isPresent());
  }

  private void verify(String provider) throws Exception {
    var count = new AtomicInteger();
    var model = new ProviderRegistry(System.getenv()).resolve(provider);
    var response =
        new ReactAgentFactory()
            .create(model, AgentToolTest.probe(count))
            .call("Call compatibility_probe exactly once and return its marker exactly.");
    assertEquals(1, count.get());
    assertTrue(response.getText().contains("ARL_M0_OK"));
  }
}
