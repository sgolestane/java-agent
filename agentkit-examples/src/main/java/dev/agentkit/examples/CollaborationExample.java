package dev.agentkit.examples;

import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.collab.Blackboard;
import dev.agentkit.core.collab.BlackboardTools;
import dev.agentkit.core.collab.Critics;
import dev.agentkit.core.collab.MessagingTools;
import dev.agentkit.core.collab.RefineLoop;
import dev.agentkit.core.collab.RefineResult;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.reliability.RetryPolicy;
import dev.agentkit.core.reliability.RetryingLlmClient;
import dev.agentkit.core.supervisor.Subagent;
import dev.agentkit.core.supervisor.SubagentRoster;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Exercises all three collaboration primitives together to produce a short brief:
 *
 * <ul>
 *   <li><b>Agent-to-agent messaging</b> — the writer can {@code send_message} a
 *       {@code researcher} peer for facts (bounded by a shared message budget).</li>
 *   <li><b>Shared workspace</b> — the writer jots findings to a {@link Blackboard}
 *       with {@code post_note} and can {@code read_board} what's there.</li>
 *   <li><b>Generator&#8596;critic refine loop</b> — a {@link RefineLoop} wraps the
 *       writer as the generator and an {@code editor} peer as the {@link Critics#agent
 *       agent critic}, revising until the editor approves or the round cap is hit.</li>
 * </ul>
 *
 * <p>Everything runs against the backend chosen by {@link ExampleBackend}.
 */
public final class CollaborationExample {

    private CollaborationExample() {
    }

    /** Wires the writer + researcher + editor collaboration over a shared {@code board}. */
    public static RefineLoop build(LlmClient llm, String model, Blackboard board) {
        // A peer the writer can message for facts.
        SubagentRoster peers = SubagentRoster.of(
                Subagent.of("researcher", "Answers factual questions with brief, concrete facts.",
                        () -> new Agent(llm, new SimpleToolRegistry(),
                                AgentConfig.builder(model).maxSteps(6)
                                        .systemPrompt("You answer factual questions concisely and concretely.")
                                        .build())));
        // Shared across every send_message tool so the whole conversation is bounded.
        AtomicInteger messageBudget = MessagingTools.budget(6);

        // The generator: a writer that can message peers and use the shared workspace.
        Supplier<Agent> writer = () -> {
            SimpleToolRegistry tools = new SimpleToolRegistry();
            tools.register(MessagingTools.sendMessageTool(peers, messageBudget));
            tools.register(BlackboardTools.postNoteTool(board, "writer"));
            tools.register(BlackboardTools.readBoardTool(board));
            return new Agent(llm, tools, AgentConfig.builder(model).maxSteps(8)
                    .systemPrompt("You are a writer. Ask the researcher (via send_message) for any facts "
                            + "you need, jot findings to the shared workspace with post_note, then write "
                            + "the final brief in exactly three sentences.")
                    .build());
        };

        // The critic: an editor peer that reviews each draft (agent-to-agent critique).
        Subagent editor = Subagent.of("editor", "Reviews drafts for clarity and accuracy",
                () -> new Agent(llm, new SimpleToolRegistry(),
                        AgentConfig.builder(model).maxSteps(4)
                                .systemPrompt("You are a meticulous editor.").build()));

        return new RefineLoop(writer, Critics.agent(editor), /* maxRounds */ 2);
    }

    /** Runs the example against the configured backend (see {@link ExampleBackend}). */
    public static void main(String[] args) {
        ExampleBackend backend = ExampleBackend.fromEnv();
        LlmClient reliable = new RetryingLlmClient(backend.llm(), RetryPolicy.defaults());

        Blackboard board = new Blackboard();
        RefineLoop loop = build(reliable, backend.model(), board);
        RefineResult result = loop.run(Goal.of(
                "Write a three-sentence brief on the benefits of durable execution for AI agents."));

        System.out.println("approved=" + result.approved() + " rounds=" + result.rounds());
        System.out.println(result.output());
        System.out.println("\n--- shared workspace ---");
        for (Blackboard.Entry entry : board.entries()) {
            System.out.println("#" + entry.id() + " [" + entry.topic() + "] by "
                    + entry.author() + ": " + entry.content());
        }
    }
}
