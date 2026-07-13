package dev.agentkit.examples;

import dev.agentkit.anthropic.AnthropicLlmClient;
import dev.agentkit.core.agent.AgentConfig;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.tool.SimpleToolRegistry;
import dev.agentkit.core.tool.ToolRegistry;
import dev.agentkit.temporal.AgentRunResult;
import dev.agentkit.temporal.DurableAgentRun;
import dev.agentkit.temporal.TemporalAgent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

/**
 * Runs the AgentKit loop durably on Temporal. This wires a worker (whose
 * activities hold the real model client and tools) and a client sharing the
 * AgentKit data converter, then starts one durable run.
 *
 * <p>Requires a reachable Temporal service (e.g. {@code temporal server
 * start-dev} on localhost) and {@code ANTHROPIC_API_KEY}; it is a {@code main}
 * demo rather than a test. The in-memory, no-server durability tests live in
 * {@code agentkit-temporal}.
 */
public final class TemporalWorkerExample {

    private static final String TASK_QUEUE = "agentkit-examples";

    private TemporalWorkerExample() {
    }

    public static void main(String[] args) {
        LlmClient llm = AnthropicLlmClient.fromEnv();
        ToolRegistry tools = new SimpleToolRegistry();

        // Client and worker must share the AgentKit data converter.
        WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(service, WorkflowClientOptions.newBuilder()
                .setDataConverter(TemporalAgent.dataConverter())
                .build());

        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(TASK_QUEUE);
        TemporalAgent.register(worker, llm, tools);
        factory.start();

        AgentConfig config = AgentConfig.builder(AnthropicLlmClient.DEFAULT_MODEL)
                .systemPrompt("You are a helpful assistant.")
                .maxSteps(8)
                .build();

        AgentRunResult result = TemporalAgent.newStub(client, TASK_QUEUE)
                .run(DurableAgentRun.of(Goal.of("Say hello and explain what durable execution is."), config));

        System.out.println("stopReason=" + result.stopReason());
        System.out.println(result.output());
        factory.shutdown();
        System.exit(0);
    }
}
