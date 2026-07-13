package dev.agentkit.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.tool.ToolSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicLlmClientTest {

    @Test
    void toParamsMapsCoreFields() {
        LlmRequest request = LlmRequest.builder("claude-opus-4-8")
                .system("be helpful")
                .maxTokens(2048)
                .addMessage(Message.user("hello"))
                .tools(List.of(new ToolSpec("search", "search the web",
                        Map.of("type", "object",
                                "properties", Map.of("q", Map.of("type", "string")),
                                "required", List.of("q")))))
                .build();

        MessageCreateParams params = AnthropicLlmClient.toParams(request);

        assertThat(params.maxTokens()).isEqualTo(2048L);
        assertThat(params.model().toString()).contains("claude-opus-4-8");
        assertThat(params.system()).isPresent();
        assertThat(params.messages()).hasSize(1);
        assertThat(params.tools()).isPresent();
        assertThat(params.tools().orElseThrow()).hasSize(1);
    }

    @Test
    void toParamsMapsToolResultMessages() {
        LlmRequest request = LlmRequest.builder("claude-opus-4-8")
                .addMessage(Message.user("go"))
                .addMessage(Message.of(Role.ASSISTANT,
                        new dev.agentkit.core.message.ToolUseBlock("t1", "search", Map.of("q", "x"))))
                .addMessage(Message.of(Role.USER, ToolResultBlock.ok("t1", "result")))
                .build();

        MessageCreateParams params = AnthropicLlmClient.toParams(request);

        assertThat(params.messages()).hasSize(3);
    }

    @Test
    void stopReasonMappingCoversAllProviderReasons() {
        assertThat(AnthropicLlmClient.mapStopReason(StopReason.TOOL_USE)).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(AnthropicLlmClient.mapStopReason(StopReason.END_TURN)).isEqualTo(LlmStopReason.END_TURN);
        assertThat(AnthropicLlmClient.mapStopReason(StopReason.STOP_SEQUENCE)).isEqualTo(LlmStopReason.END_TURN);
        assertThat(AnthropicLlmClient.mapStopReason(StopReason.MAX_TOKENS)).isEqualTo(LlmStopReason.MAX_TOKENS);
        assertThat(AnthropicLlmClient.mapStopReason(StopReason.REFUSAL)).isEqualTo(LlmStopReason.REFUSAL);
        assertThat(AnthropicLlmClient.mapStopReason(StopReason.PAUSE_TURN)).isEqualTo(LlmStopReason.PAUSE);
        assertThat(AnthropicLlmClient.mapStopReason(null)).isEqualTo(LlmStopReason.OTHER);
    }
}
