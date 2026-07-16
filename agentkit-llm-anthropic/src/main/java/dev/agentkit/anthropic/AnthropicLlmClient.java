package dev.agentkit.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
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
    private final ModelResolver modelResolver;
    private final CachePolicy cachePolicy;

    public AnthropicLlmClient(AnthropicClient client) {
        this(client, ModelResolver.IDENTITY, CachePolicy.NONE);
    }

    /**
     * Builds a client that translates each request's logical model id through
     * {@code modelResolver} before calling the API. Use this for backends where
     * the wire model id differs from the logical one (e.g. Amazon Bedrock
     * application inference profiles); the first-party API uses
     * {@link ModelResolver#IDENTITY}.
     */
    public AnthropicLlmClient(AnthropicClient client, ModelResolver modelResolver) {
        this(client, modelResolver, CachePolicy.NONE);
    }

    /**
     * As {@link #AnthropicLlmClient(AnthropicClient, ModelResolver)}, plus a
     * {@link CachePolicy} that adds prompt-cache breakpoints to each request.
     * Enabling caching is recommended for agent loops, which re-send a large stable
     * prefix every turn.
     */
    public AnthropicLlmClient(AnthropicClient client, ModelResolver modelResolver, CachePolicy cachePolicy) {
        this.client = Objects.requireNonNull(client, "client");
        this.modelResolver = Objects.requireNonNull(modelResolver, "modelResolver");
        this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
    }

    /** Builds a client from the {@code ANTHROPIC_API_KEY} environment variable (no caching). */
    public static AnthropicLlmClient fromEnv() {
        return fromEnv(CachePolicy.NONE);
    }

    /** As {@link #fromEnv()}, with the given prompt-caching {@code cachePolicy}. */
    public static AnthropicLlmClient fromEnv(CachePolicy cachePolicy) {
        return new AnthropicLlmClient(AnthropicOkHttpClient.fromEnv(), ModelResolver.IDENTITY, cachePolicy);
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            MessageCreateParams params = toParams(request, modelResolver, cachePolicy);
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
        return toParams(request, ModelResolver.IDENTITY, CachePolicy.NONE);
    }

    static MessageCreateParams toParams(LlmRequest request, ModelResolver modelResolver) {
        return toParams(request, modelResolver, CachePolicy.NONE);
    }

    /**
     * Maps the request, optionally adding prompt-cache breakpoints. The render
     * order is tools &rarr; system &rarr; messages, and a breakpoint caches
     * everything up to and including it, so the placement is:
     * <ul>
     *   <li>the <b>stable prefix</b> (tools + system) is cached by marking the
     *       system prompt (which renders after the tools); with no system prompt,
     *       the last tool is marked instead, so the tool definitions still cache;</li>
     *   <li>the <b>growing conversation</b> is cached by an explicit breakpoint on
     *       the last content block of the last message — so each turn's history is
     *       a cache read on the next turn.</li>
     * </ul>
     * That is at most two breakpoints (well under the API's limit of four). Both are
     * <em>explicit</em> per-block breakpoints (not top-level automatic caching), so
     * they work on Amazon Bedrock as well as the first-party API.
     */
    static MessageCreateParams toParams(LlmRequest request, ModelResolver modelResolver, CachePolicy cachePolicy) {
        CacheControlEphemeral cc = cachePolicy.enabled() ? cachePolicy.cacheControl() : null;

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(modelResolver.resolve(request.model()))
                .maxTokens(request.maxTokens());

        // System prompt. With caching, render it as a cache-marked text block so
        // the tools+system prefix is cached; without, the plain string form.
        request.system().ifPresent(system -> {
            if (cc != null) {
                builder.systemOfTextBlockParams(List.of(
                        TextBlockParam.builder().text(system).cacheControl(cc).build()));
            } else {
                builder.system(system);
            }
        });

        // Rolling conversation breakpoint: mark the last content block of the last
        // message (LlmRequest guarantees at least one message).
        List<Message> messages = request.messages();
        for (int i = 0; i < messages.size(); i++) {
            CacheControlEphemeral messageCc = i == messages.size() - 1 ? cc : null;
            builder.addMessage(toMessageParam(messages.get(i), messageCc));
        }

        // Tools. When caching without a system prompt, mark the last tool so the
        // tool definitions are still cached (no later prefix breakpoint covers them).
        List<ToolSpec> tools = request.tools();
        boolean markLastTool = cc != null && request.system().isEmpty() && !tools.isEmpty();
        for (int i = 0; i < tools.size(); i++) {
            boolean isLast = i == tools.size() - 1;
            builder.addTool(toTool(tools.get(i), markLastTool && isLast ? cc : null));
        }

        return builder.build();
    }

    /**
     * Maps a message. When {@code cacheControl} is non-null it is applied to the
     * <em>last</em> content block, placing an explicit cache breakpoint at the end
     * of the conversation. (A trailing unsigned thinking block is dropped and can't
     * carry a breakpoint, but that never occurs as a request's last block, which is
     * always a user turn — text or tool results.)
     */
    private static MessageParam toMessageParam(Message message, CacheControlEphemeral cacheControl) {
        MessageParam.Role role = switch (message.role()) {
            case USER -> MessageParam.Role.USER;
            case ASSISTANT -> MessageParam.Role.ASSISTANT;
            case SYSTEM -> throw new LlmException(
                    "SYSTEM messages must be provided via LlmRequest.system(), not in the message list");
        };

        List<ContentBlockParam> blocks = new ArrayList<>();
        List<ContentBlock> content = message.content();
        for (int i = 0; i < content.size(); i++) {
            CacheControlEphemeral blockCc = i == content.size() - 1 ? cacheControl : null;
            toContentBlockParam(content.get(i), blockCc).ifPresent(blocks::add);
        }
        if (blocks.isEmpty()) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text("(no content)").build()));
        }
        return MessageParam.builder().role(role).contentOfBlockParams(blocks).build();
    }

    private static java.util.Optional<ContentBlockParam> toContentBlockParam(
            ContentBlock block, CacheControlEphemeral cc) {
        return switch (block) {
            case TextBlock t -> {
                TextBlockParam.Builder b = TextBlockParam.builder().text(t.text());
                if (cc != null) {
                    b.cacheControl(cc);
                }
                yield java.util.Optional.of(ContentBlockParam.ofText(b.build()));
            }
            case ToolUseBlock u ->
                    java.util.Optional.of(ContentBlockParam.ofToolUse(toToolUseParam(u, cc)));
            case ToolResultBlock r -> {
                ToolResultBlockParam.Builder b = ToolResultBlockParam.builder()
                        .toolUseId(r.toolUseId())
                        .content(r.content())
                        .isError(r.isError());
                if (cc != null) {
                    b.cacheControl(cc);
                }
                yield java.util.Optional.of(ContentBlockParam.ofToolResult(b.build()));
            }
            case ThinkingBlock th -> th.signature().isBlank()
                    ? java.util.Optional.empty() // cannot replay an unsigned thinking block
                    : java.util.Optional.of(ContentBlockParam.ofThinking(
                            ThinkingBlockParam.builder()
                                    .thinking(th.thinking())
                                    .signature(th.signature())
                                    .build()));
        };
    }

    private static ToolUseBlockParam toToolUseParam(ToolUseBlock use, CacheControlEphemeral cc) {
        ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
        use.input().forEach((k, v) -> input.putAdditionalProperty(k, JsonValue.from(v)));
        ToolUseBlockParam.Builder builder = ToolUseBlockParam.builder()
                .id(use.id())
                .name(use.name())
                .input(input.build());
        if (cc != null) {
            builder.cacheControl(cc);
        }
        return builder.build();
    }

    private static Tool toTool(ToolSpec spec, CacheControlEphemeral cacheControl) {
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

        Tool.InputSchema.Builder inputSchemaBuilder = Tool.InputSchema.builder()
                .properties(properties.build())
                .required(required);

        // Forward any additional top-level schema keys (e.g. additionalProperties,
        // $defs) so richer schemas are not silently weakened.
        java.util.Map<String, JsonValue> extras = new java.util.LinkedHashMap<>();
        schema.forEach((k, v) -> {
            if (!"type".equals(k) && !"properties".equals(k) && !"required".equals(k)) {
                extras.put(k, JsonValue.from(v));
            }
        });
        if (!extras.isEmpty()) {
            inputSchemaBuilder.putAllAdditionalProperties(extras);
        }
        Tool.InputSchema inputSchema = inputSchemaBuilder.build();

        Tool.Builder toolBuilder = Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(inputSchema);
        if (cacheControl != null) {
            toolBuilder.cacheControl(cacheControl);
        }
        return toolBuilder.build();
    }

    // --- response mapping ---------------------------------------------------

    private LlmResponse toLlmResponse(com.anthropic.models.messages.Message response) {
        // NOTE: extended thinking is not enabled on requests yet, so responses do
        // not carry redacted_thinking blocks. Before enabling thinking, add a core
        // type to preserve redacted blocks for replay (otherwise the API rejects a
        // replayed assistant turn that dropped them).
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
        StopReason rawReason = response.stopReason().orElse(null);
        LlmStopReason stopReason = mapStopReason(rawReason);
        TokenUsage usage = new TokenUsage(response.usage().inputTokens(), response.usage().outputTokens());
        return new LlmResponse(assistant, stopReason, usage,
                java.util.Optional.ofNullable(rawReason).map(Object::toString));
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
