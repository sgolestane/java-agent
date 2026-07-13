package dev.agentkit.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmException;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ThinkingBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.tool.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An {@link LlmClient} backed by the official Anthropic Java SDK.
 *
 * <p>Translates AgentKit's provider-agnostic {@link LlmRequest} into a
 * {@code MessageCreateParams}, invokes the Messages API, and normalises the
 * response back into an {@link LlmResponse}. The default model is
 * {@value #DEFAULT_MODEL}.
 *
 * <p>Only the content-block types AgentKit uses — text, thinking, tool-use, and
 * tool-result — are mapped; other provider block types in a response are ignored.
 */
public final class AnthropicLlmClient implements LlmClient {

    /** The recommended default model. */
    public static final String DEFAULT_MODEL = "claude-opus-4-8";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AnthropicClient client;

    public AnthropicLlmClient(AnthropicClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Builds a client from the {@code ANTHROPIC_API_KEY} environment variable. */
    public static AnthropicLlmClient fromEnv() {
        return new AnthropicLlmClient(AnthropicOkHttpClient.fromEnv());
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            MessageCreateParams params = toParams(request);
            com.anthropic.models.messages.Message response = client.messages().create(params);
            return toLlmResponse(response);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("Anthropic message call failed: " + e.getMessage(), e);
        }
    }

    // --- request mapping ----------------------------------------------------

    static MessageCreateParams toParams(LlmRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens());
        request.system().ifPresent(builder::system);
        for (Message message : request.messages()) {
            builder.addMessage(toMessageParam(message));
        }
        for (ToolSpec spec : request.tools()) {
            builder.addTool(toTool(spec));
        }
        return builder.build();
    }

    private static MessageParam toMessageParam(Message message) {
        MessageParam.Role role = switch (message.role()) {
            case USER -> MessageParam.Role.USER;
            case ASSISTANT -> MessageParam.Role.ASSISTANT;
            case SYSTEM -> throw new LlmException(
                    "SYSTEM messages must be provided via LlmRequest.system(), not in the message list");
        };

        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            toContentBlockParam(block).ifPresent(blocks::add);
        }
        if (blocks.isEmpty()) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text("").build()));
        }
        return MessageParam.builder().role(role).contentOfBlockParams(blocks).build();
    }

    private static java.util.Optional<ContentBlockParam> toContentBlockParam(ContentBlock block) {
        return switch (block) {
            case TextBlock t ->
                    java.util.Optional.of(ContentBlockParam.ofText(
                            TextBlockParam.builder().text(t.text()).build()));
            case ToolUseBlock u ->
                    java.util.Optional.of(ContentBlockParam.ofToolUse(toToolUseParam(u)));
            case ToolResultBlock r ->
                    java.util.Optional.of(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(r.toolUseId())
                                    .content(r.content())
                                    .isError(r.isError())
                                    .build()));
            case ThinkingBlock th -> th.signature().isBlank()
                    ? java.util.Optional.empty() // cannot replay an unsigned thinking block
                    : java.util.Optional.of(ContentBlockParam.ofThinking(
                            ThinkingBlockParam.builder()
                                    .thinking(th.thinking())
                                    .signature(th.signature())
                                    .build()));
        };
    }

    private static ToolUseBlockParam toToolUseParam(ToolUseBlock use) {
        ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
        use.input().forEach((k, v) -> input.putAdditionalProperty(k, JsonValue.from(v)));
        return ToolUseBlockParam.builder()
                .id(use.id())
                .name(use.name())
                .input(input.build())
                .build();
    }

    private static Tool toTool(ToolSpec spec) {
        Map<String, Object> schema = spec.inputSchema();

        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        if (schema.get("properties") instanceof Map<?, ?> props) {
            props.forEach((k, v) -> properties.putAdditionalProperty(String.valueOf(k), JsonValue.from(v)));
        }

        List<String> required = new ArrayList<>();
        if (schema.get("required") instanceof List<?> req) {
            for (Object item : req) {
                if (item != null) {
                    required.add(item.toString());
                }
            }
        }

        Tool.InputSchema inputSchema = Tool.InputSchema.builder()
                .properties(properties.build())
                .required(required)
                .build();

        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(inputSchema)
                .build();
    }

    // --- response mapping ---------------------------------------------------

    private LlmResponse toLlmResponse(com.anthropic.models.messages.Message response) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (com.anthropic.models.messages.ContentBlock block : response.content()) {
            block.text().ifPresent(t -> blocks.add(TextBlock.of(t.text())));
            block.thinking().ifPresent(t -> blocks.add(new ThinkingBlock(t.thinking(), t.signature())));
            block.toolUse().ifPresent(u -> blocks.add(
                    new ToolUseBlock(u.id(), u.name(), toArgumentMap(u))));
        }
        if (blocks.isEmpty()) {
            blocks.add(TextBlock.of(""));
        }

        Message assistant = Message.of(Role.ASSISTANT, blocks);
        LlmStopReason stopReason = mapStopReason(response.stopReason().orElse(null));
        TokenUsage usage = new TokenUsage(response.usage().inputTokens(), response.usage().outputTokens());
        return new LlmResponse(assistant, stopReason, usage);
    }

    private static Map<String, Object> toArgumentMap(com.anthropic.models.messages.ToolUseBlock use) {
        try {
            Map<String, Object> map = use._input().convert(MAP_TYPE);
            return map == null ? Map.of() : map;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    static LlmStopReason mapStopReason(StopReason reason) {
        if (reason == null) {
            return LlmStopReason.OTHER;
        }
        if (reason.equals(StopReason.TOOL_USE)) {
            return LlmStopReason.TOOL_USE;
        }
        if (reason.equals(StopReason.END_TURN) || reason.equals(StopReason.STOP_SEQUENCE)) {
            return LlmStopReason.END_TURN;
        }
        if (reason.equals(StopReason.MAX_TOKENS)) {
            return LlmStopReason.MAX_TOKENS;
        }
        if (reason.equals(StopReason.REFUSAL)) {
            return LlmStopReason.REFUSAL;
        }
        if (reason.equals(StopReason.PAUSE_TURN)) {
            return LlmStopReason.PAUSE;
        }
        return LlmStopReason.OTHER;
    }
}
