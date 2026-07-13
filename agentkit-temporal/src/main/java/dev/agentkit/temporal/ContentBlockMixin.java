package dev.agentkit.temporal;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.agentkit.core.message.TextBlock;
import dev.agentkit.core.message.ThinkingBlock;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;

/**
 * Jackson mix-in that teaches the durable data converter how to (de)serialize the
 * sealed {@code ContentBlock} hierarchy polymorphically, via a {@code "@type"}
 * discriminator. Applied in {@link DurableJson} so the core module stays free of
 * any Jackson or Temporal dependency.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
        @JsonSubTypes.Type(value = ThinkingBlock.class, name = "thinking"),
        @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
        @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result"),
})
interface ContentBlockMixin {
}
