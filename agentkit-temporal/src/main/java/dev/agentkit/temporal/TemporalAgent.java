package dev.agentkit.temporal;

import dev.agentkit.core.llm.LlmClient;
import dev.agentkit.core.tool.ToolRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.worker.Worker;
import java.util.Objects;

/**
 * Ergonomic wiring for running the AgentKit loop on Temporal.
 *
 * <p>The moving parts:
 * <ul>
 *   <li>{@link #dataConverter()} — the shared converter; set it on the
 *       {@code WorkflowClient} (client side) and the worker's client.</li>
 *   <li>{@link #register(Worker, LlmClient, ToolRegistry)} — registers the
 *       workflow implementation and the two activity implementations (which hold
 *       your real model client and tools) on a worker.</li>
 *   <li>{@link #newStub(WorkflowClient, String)} — a typed workflow stub to start
 *       a run.</li>
 * </ul>
 *
 * <p>Typical worker + client setup:
 * <pre>{@code
 * WorkflowClientOptions opts = WorkflowClientOptions.newBuilder()
 *         .setDataConverter(TemporalAgent.dataConverter()).build();
 * WorkflowClient client = WorkflowClient.newInstance(service, opts);
 * WorkerFactory factory = WorkerFactory.newInstance(client);
 * Worker worker = factory.newWorker("agentkit");
 * TemporalAgent.register(worker, llmClient, toolRegistry);
 * factory.start();
 *
 * AgentRunResult result = TemporalAgent.newStub(client, "agentkit")
 *         .run(DurableAgentRun.of(goal, config, toolSpecs));
 * }</pre>
 */
public final class TemporalAgent {

    private TemporalAgent() {
    }

    /** The data converter to install on every client that touches the agent. */
    public static DataConverter dataConverter() {
        return DurableJson.dataConverter();
    }

    /**
     * Registers the durable agent's workflow and activity implementations on
     * {@code worker}. The activity implementations carry the real, side-effecting
     * collaborators — the model client and the tool registry.
     */
    public static void register(Worker worker, LlmClient llm, ToolRegistry tools) {
        Objects.requireNonNull(worker, "worker");
        worker.registerWorkflowImplementationTypes(AgentWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new LlmActivitiesImpl(llm), new ToolActivitiesImpl(tools));
    }

    /** A typed workflow stub bound to {@code taskQueue} for starting a run. */
    public static AgentWorkflow newStub(WorkflowClient client, String taskQueue) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(taskQueue, "taskQueue");
        return client.newWorkflowStub(AgentWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build());
    }
}
