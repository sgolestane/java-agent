package dev.agentkit.examples;

import dev.agentkit.anthropic.AnthropicLlmClient;
import dev.agentkit.core.agent.Agent;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.supervisor.DelegatedTask;
import dev.agentkit.core.supervisor.Subagent;
import dev.agentkit.core.supervisor.SubagentRoster;
import dev.agentkit.core.supervisor.SupervisionResult;
import dev.agentkit.core.supervisor.Supervisor;
import dev.agentkit.core.supervisor.Synthesizers;
import dev.agentkit.core.tool.SimpleToolRegistry;
import java.util.List;

/**
 * A supervisor that decomposes a briefing goal across two specialised subagents
 * and synthesises their results with a model. Demonstrates the programmatic
 * fan-out path: independent subgoals run concurrently, each on a fresh agent.
 */
public final class SupervisorExample {

    private SupervisorExample() {
    }

    /** A supervisor over a researcher + writer roster, synthesising with the model. */
    public static Supervisor build(LlmClient llm, String model) {
        AgentConfig config = AgentConfig.builder(model).maxSteps(8).build();

        SubagentRoster roster = SubagentRoster.of(
                Subagent.of("researcher", "Gathers and summarises the key facts on a topic",
                        () -> new Agent(llm, new SimpleToolRegistry(),
                                AgentConfig.builder(model).maxSteps(8)
                                        .systemPrompt("You research and report concise facts.").build())),
                Subagent.of("writer", "Turns notes into a short, polished brief",
                        () -> new Agent(llm, new SimpleToolRegistry(),
                                AgentConfig.builder(model).maxSteps(8)
                                        .systemPrompt("You write concise, well-structured prose.").build())));

        return Supervisor.builder(roster)
                .synthesizer(Synthesizers.llm(llm, model))
                .maxConcurrency(4)
                .build();
    }

    /** Runs the example against the real Anthropic API (reads {@code ANTHROPIC_API_KEY}). */
    public static void main(String[] args) {
        LlmClient llm = AnthropicLlmClient.fromEnv();
        Supervisor supervisor = build(llm, AnthropicLlmClient.DEFAULT_MODEL);

        Goal goal = Goal.of("Produce a one-paragraph brief on the benefits of durable execution.");
        SupervisionResult result = supervisor.fanOut(goal, List.of(
                DelegatedTask.of("researcher", "List the key benefits of durable execution for agents."),
                DelegatedTask.of("writer", "Draft a one-paragraph brief from the researcher's notes.")));

        System.out.println("allSucceeded=" + result.allSucceeded());
        System.out.println(result.output());
    }
}
