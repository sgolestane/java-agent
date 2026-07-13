package dev.agentkit.core.agent;

import dev.agentkit.core.context.ContextStrategy;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.llm.LlmRequest;
import dev.agentkit.core.reliability.GateResult;
import dev.agentkit.core.reliability.ToolGate;
import dev.agentkit.core.llm.LlmResponse;
import dev.agentkit.core.llm.LlmStopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Conversation;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.tool.Tool;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The core agentic loop: goal in, {@link AgentResult} out.
 *
 * <p>Each step calls the model with the running conversation and the registry's
 * advertised tool specs. If the model requests tools, they are executed and their
 * results are fed back; otherwise the run finishes. The loop is bounded by
 * {@link AgentConfig#maxSteps()} and never lets a single tool failure abort the
 * run — a thrown tool exception becomes an error {@link ToolResult} the model can
 * react to.
 *
 * <p>This class is provider-agnostic: it depends only on {@link LlmClient} and
 * {@link ToolRegistry}. The same loop runs in-process here and, in a later phase,
 * inside a Temporal workflow.
 */
public final class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final LlmClient llm;
    private final ToolRegistry tools;
    private final AgentConfig config;
    private final AgentObserver observer;
    private final ContextStrategy contextStrategy;
    private final ToolGate toolGate;

    public Agent(LlmClient llm, ToolRegistry tools, AgentConfig config) {
        this(llm, tools, config, AgentObserver.NONE, ContextStrategy.IDENTITY);
    }

    public Agent(LlmClient llm, ToolRegistry tools, AgentConfig config, AgentObserver observer) {
        this(llm, tools, config, observer, ContextStrategy.IDENTITY);
    }

    public Agent(LlmClient llm, ToolRegistry tools, AgentConfig config, AgentObserver observer,
                 ContextStrategy contextStrategy) {
        this(builder(llm, tools, config).observer(observer).contextStrategy(contextStrategy));
    }

    private Agent(Builder b) {
        this.llm = Objects.requireNonNull(b.llm, "llm");
        this.tools = Objects.requireNonNull(b.tools, "tools");
        this.config = Objects.requireNonNull(b.config, "config");
        this.observer = Objects.requireNonNull(b.observer, "observer");
        this.contextStrategy = Objects.requireNonNull(b.contextStrategy, "contextStrategy");
        this.toolGate = Objects.requireNonNull(b.toolGate, "toolGate");
    }

    public static Builder builder(LlmClient llm, ToolRegistry tools, AgentConfig config) {
        return new Builder(llm, tools, config);
    }

    /** Fluent construction with optional observer, context strategy, and tool gate. */
    public static final class Builder {
        private final LlmClient llm;
        private final ToolRegistry tools;
        private final AgentConfig config;
        private AgentObserver observer = AgentObserver.NONE;
        private ContextStrategy contextStrategy = ContextStrategy.IDENTITY;
        private ToolGate toolGate = ToolGate.ALLOW_ALL;

        private Builder(LlmClient llm, ToolRegistry tools, AgentConfig config) {
            this.llm = llm;
            this.tools = tools;
            this.config = config;
        }

        public Builder observer(AgentObserver observer) {
            this.observer = observer;
            return this;
        }

        public Builder contextStrategy(ContextStrategy contextStrategy) {
            this.contextStrategy = contextStrategy;
            return this;
        }

        public Builder toolGate(ToolGate toolGate) {
            this.toolGate = toolGate;
            return this;
        }

        public Agent build() {
            return new Agent(this);
        }
    }

    /** Runs the agent to pursue {@code goal}. */
    public AgentResult run(Goal goal) {
        Objects.requireNonNull(goal, "goal");
        observer.onStart(goal);

        Conversation conversation = new Conversation();
        conversation.append(Message.user(renderGoal(goal)));

        TokenUsage totalUsage = TokenUsage.ZERO;
        int steps = 0;
        String lastText = "";

        while (steps < config.maxSteps()) {
            // Engineer the context (edit/compact/...) and persist it, so the
            // transformation is applied once rather than recomputed each turn.
            List<Message> prepared = contextStrategy.prepare(conversation.messages());
            if (prepared.isEmpty()) {
                var ex = new IllegalStateException("Context strategy produced an empty message list");
                return finish(AgentResult.failed(ex, steps, totalUsage));
            }
            conversation.replaceAll(prepared);

            LlmResponse response;
            try {
                response = llm.generate(buildRequest(conversation));
            } catch (RuntimeException e) {
                log.warn("Model call failed on step {}", steps + 1, e);
                return finish(AgentResult.failed(e, steps, totalUsage));
            }

            steps++;
            totalUsage = totalUsage.plus(response.usage());
            conversation.append(response.message());
            lastText = response.message().text();
            observer.onModelResponse(steps, response);
            log.debug("Step {} stopReason={} usage={}", steps, response.stopReason(), totalUsage);

            switch (response.stopReason()) {
                case REFUSAL -> {
                    return finish(AgentResult.stopped(StopReason.REFUSED, lastText, steps, totalUsage));
                }
                case PAUSE -> {
                    // A resumable pause (e.g. a long-running server-side tool). This
                    // in-process loop does not resume; a durable runner (Temporal) can.
                    return finish(AgentResult.stopped(StopReason.PAUSED, lastText, steps, totalUsage));
                }
                case MAX_TOKENS -> {
                    // Output was truncated. Any tool call in this turn may be
                    // incomplete, so stop rather than execute a partial call.
                    return finish(AgentResult.stopped(StopReason.BUDGET_EXHAUSTED, lastText, steps, totalUsage));
                }
                default -> {
                    // END_TURN / OTHER: finish if no tools, else run them and continue.
                }
            }

            List<ToolUseBlock> toolUses = toolUses(response.message());
            if (toolUses.isEmpty()) {
                return finish(AgentResult.completed(lastText, steps, totalUsage));
            }

            conversation.append(executeTools(steps, toolUses));
        }

        return finish(AgentResult.stopped(StopReason.MAX_STEPS, lastText, steps, totalUsage));
    }

    private LlmRequest buildRequest(Conversation conversation) {
        LlmRequest.Builder builder = LlmRequest.builder(config.model())
                .messages(conversation.messages())
                .tools(tools.advertisedSpecs())
                .maxTokens(config.maxTokens());
        config.systemPromptValue().ifPresent(builder::system);
        config.options().forEach(builder::option);
        return builder.build();
    }

    private Message executeTools(int step, List<ToolUseBlock> toolUses) {
        List<ContentBlock> results = new ArrayList<>(toolUses.size());
        for (ToolUseBlock use : toolUses) {
            ToolInvocation invocation = new ToolInvocation(use.id(), use.name(), use.input());
            observer.onToolInvocation(step, invocation);
            ToolResult result = runTool(invocation);
            observer.onToolResult(step, invocation, result);
            results.add(new ToolResultBlock(use.id(), result.content(), result.isError()));
        }
        return Message.of(Role.USER, results);
    }

    private ToolResult runTool(ToolInvocation invocation) {
        Optional<Tool> tool = tools.find(invocation.name());
        if (tool.isEmpty()) {
            return ToolResult.error("Unknown tool: '" + invocation.name() + "'");
        }
        GateResult gate = toolGate.evaluate(invocation);
        if (!gate.allowed()) {
            log.info("Tool '{}' blocked by gate: {}", invocation.name(), gate.reason());
            return ToolResult.error(gate.reason());
        }
        try {
            return tool.get().execute(invocation);
        } catch (RuntimeException e) {
            log.warn("Tool '{}' threw", invocation.name(), e);
            return ToolResult.error("Tool '" + invocation.name() + "' failed: " + e.getMessage());
        }
    }

    private AgentResult finish(AgentResult result) {
        observer.onFinish(result);
        return result;
    }

    private static List<ToolUseBlock> toolUses(Message message) {
        List<ToolUseBlock> uses = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block instanceof ToolUseBlock use) {
                uses.add(use);
            }
        }
        return uses;
    }

    private static String renderGoal(Goal goal) {
        if (goal.parameters().isEmpty()) {
            return goal.description();
        }
        StringBuilder sb = new StringBuilder(goal.description()).append("\n\nParameters:");
        goal.parameters().forEach((k, v) -> sb.append("\n- ").append(k).append(": ").append(v));
        return sb.toString();
    }
}
