package dev.arl.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.arl.llm.ProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    classes = ArlApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@DirtiesContext
class StartupTest {
  @Autowired ProviderRegistry registry;

  @Test
  void bootStartsOnJava27() {
    assertEquals(2, registry.availableProviders().size());
    assertNotNull(registry.availableProviders().getFirst().model());
  }
}
