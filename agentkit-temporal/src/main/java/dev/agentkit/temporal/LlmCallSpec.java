package dev.agentkit.temporal;

import dev.agentkit.core.message.Message;
import dev.agentkit.core.tool.ToolSpec;
import java.util.List;
import java.util.Map;

/**
 * The serializable input to the LLM activity: everything the worker needs to
 * rebuild an {@code LlmRequest} for one model turn.
 *
 * <p>The workflow owns the conversation (durable, replayed from history) and
 * passes it to the activity per turn, along with the run's model parameters and
 * advertised tool specs. Keeping this a flat record of JSON-friendly types lets
 * Temporal's default converter serialize it without custom handling beyond the
 * {@code ContentBlock} polymorphism configured in {@link DurableJson}.
 *
 * @param model        the model identifier
 * @param system       the system prompt, or {@code null} for none
 * @param conversation the running message history for this turn
 * @param tools        the advertised tool specs
 * @param maxTokens    the per-turn output token limit
 * @param options      provider-specific options
 */
public record LlmCallSpec(String model, String system, List<Message> conversation,
                          List<ToolSpec> tools, int maxTokens, Map<String, Object> options) {

    public LlmCallSpec {
        conversation = List.copyOf(conversation);
        tools = List.copyOf(tools);
        options = Map.copyOf(options);
    }
}
