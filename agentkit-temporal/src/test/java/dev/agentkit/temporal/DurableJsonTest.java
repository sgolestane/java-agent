package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ThinkingBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the sealed {@code ContentBlock} hierarchy round-trips through the
 * durable converter's object mapper — the one type the default converter cannot
 * handle on its own.
 */
class DurableJsonTest {

    private final ObjectMapper mapper = DurableJson.objectMapper();

    private Message roundTrip(Message message) throws Exception {
        String json = mapper.writeValueAsString(message);
        return mapper.readValue(json, Message.class);
    }

    @Test
    void roundTripsEveryContentBlockType() throws Exception {
        Message original = Message.of(Role.ASSISTANT, List.of(
                TextBlock.of("hello"),
                new ThinkingBlock("pondering", "sig"),
                new ToolUseBlock("t1", "search", Map.of("q", "cats")),
                new ToolResultBlock("t1", "found", true)));

        Message restored = roundTrip(original);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void preservesToolUseInputMap() throws Exception {
        Message original = Message.of(Role.ASSISTANT,
                new ToolUseBlock("t1", "calc", Map.of("a", 2, "b", "three")));

        Message restored = roundTrip(original);

        ToolUseBlock block = (ToolUseBlock) restored.content().get(0);
        assertThat(block.input()).containsEntry("b", "three");
        assertThat(block.name()).isEqualTo("calc");
    }

    @Test
    void discriminatorIsWrittenIntoTheJson() throws Exception {
        String json = mapper.writeValueAsString(
                Message.of(Role.USER, List.of(TextBlock.of("hi"))));
        assertThat(json).contains("\"@type\":\"text\"");
    }
}
