package dev.arl;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;

class CompatibilityTest {
  @Test
  void springCanReadJava27Bytecode() throws Exception {
    var reader = new org.springframework.core.type.classreading.SimpleMetadataReaderFactory();
    assertEquals(
        getClass().getName(),
        reader.getMetadataReader(getClass().getName()).getClassMetadata().getClassName());
  }

  @Test
  void invokesRealReactAgentWithOfflineModel() throws Exception {
    ChatModel model =
        prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("M0_OK"))));
    var agent = ReactAgent.builder().name("m0_probe").model(model).build();
    assertEquals("M0_OK", agent.call("Compatibility probe").getText());
  }
}
