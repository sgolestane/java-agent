package dev.agentkit.temporal;

import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.TokenUsage;
import dev.agentkit.core.message.ContentBlock;
import dev.agentkit.core.message.Message;
import dev.agentkit.core.message.Role;
import dev.agentkit.core.message.ToolResultBlock;
import dev.agentkit.core.message.ToolUseBlock;
import dev.agentkit.core.tool.ToolInvocation;
import dev.agentkit.core.tool.ToolResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;

/**
 * Deterministic implementation of {@link AgentWorkflow}. It mirrors the
 * in-process {@code Agent} loop step-for-step, but delegates the two
 * non-deterministic / side-effecting operations — model inference and tool
 * execution — to activities. All control flow, the conversation history, and the
 * step/usage counters live in the workflow, so Temporal can replay them exactly.
 *
 * <p>The loop deliberately does not run a context strategy: compaction issues its
 * own model call and must therefore be an activity, which is left to a later
 * iteration. Verification and tool gating likewise wrap the loop rather than
 * living inside it.
 */
public final class AgentWorkflowImpl implements AgentWorkflow {

    private static final Logger log = Workflow.getLogger(AgentWorkflowImpl.class);

    @Override
    public AgentRunResult run(DurableAgentRun input) {
        AgentConfig config = input.config();
        LlmActivities llm = Workflow.newActivityStub(LlmActivities.class, llmOptions(input.options()));
        ToolActivities toolActivities =
                Workflow.newActivityStub(ToolActivities.class, toolOptions(input.options()));

        List<Message> conversation = new ArrayList<>();
        conversation.add(Message.user(renderGoal(input.goal())));

        TokenUsage totalUsage = TokenUsage.ZERO;
        int steps = 0;
        String lastText = "";

        while (steps < config.maxSteps()) {
            LlmCallSpec spec = new LlmCallSpec(config.model(), config.systemPrompt(),
                    conversation, input.tools(), config.maxTokens(), config.options());
            LlmTurn turn = llm.generate(spec);

            steps++;
            totalUsage = totalUsage.plus(turn.usage());
            conversation.add(turn.message());
            lastText = turn.message().text();
            log.debug("Durable step {} stopReason={}", steps, turn.stopReason());

            switch (turn.stopReason()) {
                case REFUSAL -> {
                    return AgentRunResult.of(StopReason.REFUSED, lastText, steps, totalUsage);
                }
                case PAUSE -> {
                    // A resumable pause; this loop stops rather than blocking a worker.
                    return AgentRunResult.of(StopReason.PAUSED, lastText, steps, totalUsage);
                }
                case MAX_TOKENS -> {
                    // Output truncated: a tool call this turn may be incomplete, so stop.
                    return AgentRunResult.of(StopReason.BUDGET_EXHAUSTED, lastText, steps, totalUsage);
                }
                default -> {
                    // END_TURN / OTHER: finish if no tools, else run them and continue.
                }
            }

            List<ToolUseBlock> toolUses = toolUses(turn.message());
            if (toolUses.isEmpty()) {
                return AgentRunResult.of(StopReason.COMPLETED, lastText, steps, totalUsage);
            }

            List<ContentBlock> results = new ArrayList<>(toolUses.size());
            for (ToolUseBlock use : toolUses) {
                ToolResult result = toolActivities.executeTool(
                        new ToolInvocation(use.id(), use.name(), use.input()));
                results.add(new ToolResultBlock(use.id(), result.content(), result.isError()));
            }
            conversation.add(Message.of(Role.USER, results));
        }

        return AgentRunResult.of(StopReason.MAX_STEPS, lastText, steps, totalUsage);
    }

    private static ActivityOptions llmOptions(DurableAgentOptions options) {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(options.llmStartToCloseSeconds()))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(options.llmMaxAttempts())
                        .build())
                .build();
    }

    private static ActivityOptions toolOptions(DurableAgentOptions options) {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(options.toolStartToCloseSeconds()))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(options.toolMaxAttempts())
                        .build())
                .build();
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
