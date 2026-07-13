package dev.agentkit.core.supervisor;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.Goal;
import dev.agentkit.core.llm.TokenUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates a roster of {@link Subagent}s: given a goal already decomposed
 * into {@link DelegatedTask}s, it fans the subgoals out (in parallel by default),
 * collects each {@link SubagentOutcome}, and hands them to a {@link Synthesizer}
 * for a single final answer — the delegate/collect/synthesize half of
 * supervision.
 *
 * <p><strong>Decomposition</strong> — turning one goal into subgoals — has two
 * supported paths:
 * <ul>
 *   <li><em>Programmatic:</em> the caller builds the {@link DelegatedTask} list
 *       and calls {@link #fanOut}. Best when the split is known up front and the
 *       subgoals are independent, so they can run concurrently.</li>
 *   <li><em>Model-driven:</em> wire {@link SubagentTools#delegateTool} into an
 *       ordinary supervisor {@link dev.agentkit.core.agent.Agent}; its model
 *       decides which subagents to call and with what subgoals, one at a time.
 *       Best when the split depends on intermediate results.</li>
 * </ul>
 *
 * <p>Independent subgoals run concurrently on an injectable executor (a
 * per-call virtual-thread executor by default). Because each {@link Subagent}
 * builds a fresh agent per delegation, parallel fan-out shares no per-run state.
 * A subagent that fails does not abort the others: its failure is captured in its
 * outcome and passed to the synthesizer, which decides how to present the gap.
 *
 * <p><strong>Reliability bounds (both opt-in).</strong> An optional
 * {@link Builder#maxConcurrency(int)} caps how many subagents run at once, so a
 * large decomposition does not fire unbounded simultaneous LLM calls at a
 * rate-limited backend. An optional {@link Builder#timeout(Duration)} sets an
 * overall deadline for the whole fan-out: a subagent still running when it
 * elapses is cancelled and recorded as a failed (timed-out) outcome rather than
 * stranding the others. On any exit — normal, timeout, or an unexpected error —
 * still-running futures are cancelled before {@code fanOut} returns.
 */
public final class Supervisor {

    private static final Logger log = LoggerFactory.getLogger(Supervisor.class);

    private final SubagentRoster roster;
    private final Synthesizer synthesizer;
    private final ExecutorService executor; // nullable: null => a fresh per-call executor
    private final int maxConcurrency;        // 0 => unbounded
    private final Duration timeout;          // nullable => no deadline

    private Supervisor(Builder b) {
        this.roster = Objects.requireNonNull(b.roster, "roster");
        this.synthesizer = Objects.requireNonNull(b.synthesizer, "synthesizer");
        this.executor = b.executor;
        this.maxConcurrency = b.maxConcurrency;
        this.timeout = b.timeout;
    }

    public static Builder builder(SubagentRoster roster) {
        return new Builder(roster);
    }

    /** A supervisor over {@code roster} that concatenates subagent outputs. */
    public static Supervisor of(SubagentRoster roster) {
        return builder(roster).build();
    }

    /**
     * Runs each task's subagent (concurrently), collects the outcomes in task
     * order, and synthesizes a final answer.
     *
     * @throws IllegalArgumentException if a task names a subagent absent from the
     *                                  roster — a caller/configuration error caught
     *                                  before any subagent runs
     */
    public SupervisionResult fanOut(Goal original, List<DelegatedTask> tasks) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(tasks, "tasks");
        List<DelegatedTask> work = List.copyOf(tasks);
        validateRouting(work);

        List<SubagentOutcome> outcomes = work.isEmpty() ? List.of() : execute(work);

        int totalSteps = 0;
        TokenUsage totalUsage = TokenUsage.ZERO;
        for (SubagentOutcome outcome : outcomes) {
            totalSteps += outcome.result().steps();
            totalUsage = totalUsage.plus(outcome.result().usage());
        }

        String output = synthesizer.synthesize(original, outcomes);
        return new SupervisionResult(output, outcomes, totalSteps, totalUsage);
    }

    private void validateRouting(List<DelegatedTask> tasks) {
        for (DelegatedTask task : tasks) {
            if (roster.find(task.subagentName()).isEmpty()) {
                throw new IllegalArgumentException("No subagent named '" + task.subagentName()
                        + "' in the roster " + roster.names());
            }
        }
    }

    private List<SubagentOutcome> execute(List<DelegatedTask> tasks) {
        if (executor != null) {
            return runOn(executor, tasks);
        }
        try (ExecutorService perCall = Executors.newVirtualThreadPerTaskExecutor()) {
            return runOn(perCall, tasks);
        }
    }

    private List<SubagentOutcome> runOn(ExecutorService exec, List<DelegatedTask> tasks) {
        Semaphore gate = maxConcurrency > 0 ? new Semaphore(maxConcurrency) : null;
        long deadlineNanos = timeout != null ? System.nanoTime() + timeout.toNanos() : 0L;

        List<Future<SubagentOutcome>> futures = new ArrayList<>(tasks.size());
        for (DelegatedTask task : tasks) {
            futures.add(exec.submit(() -> runOne(task, gate)));
        }
        try {
            List<SubagentOutcome> outcomes = new ArrayList<>(tasks.size());
            for (int i = 0; i < tasks.size(); i++) {
                outcomes.add(await(tasks.get(i), futures.get(i), deadlineNanos));
            }
            return outcomes;
        } finally {
            // Normal exit leaves nothing running; on timeout or an unexpected error
            // this frees any subagent still in flight instead of leaking it.
            for (Future<SubagentOutcome> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }
    }

    private SubagentOutcome runOne(DelegatedTask task, Semaphore gate) {
        if (gate != null) {
            try {
                gate.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return timedOut(task); // cancelled while waiting for a concurrency slot
            }
        }
        try {
            AgentResult result;
            try {
                result = roster.find(task.subagentName()).orElseThrow().handle(task.goal());
            } catch (RuntimeException e) {
                // A subagent must never take the whole fan-out down; record and continue.
                log.warn("Subagent '{}' threw during delegation", task.subagentName(), e);
                result = AgentResult.failed(e, 0);
            }
            return new SubagentOutcome(task.subagentName(), task.goal(), result);
        } finally {
            if (gate != null) {
                gate.release();
            }
        }
    }

    private SubagentOutcome await(DelegatedTask task, Future<SubagentOutcome> future, long deadlineNanos) {
        try {
            if (timeout == null) {
                return future.get();
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                future.cancel(true);
                return timedOut(task);
            }
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Subagent '{}' timed out after {}", task.subagentName(), timeout);
            return timedOut(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting a subagent", e);
        } catch (ExecutionException e) {
            // runOne catches RuntimeExceptions itself, so this is an unexpected escape.
            Throwable cause = e.getCause();
            throw new IllegalStateException("Subagent execution failed unexpectedly",
                    cause != null ? cause : e);
        }
    }

    private static SubagentOutcome timedOut(DelegatedTask task) {
        return new SubagentOutcome(task.subagentName(), task.goal(),
                AgentResult.failed(new TimeoutException("Subagent timed out"), 0));
    }

    /** Fluent construction; {@code synthesizer} defaults to concatenation. */
    public static final class Builder {
        private final SubagentRoster roster;
        private Synthesizer synthesizer = Synthesizers.concatenating();
        private ExecutorService executor;
        private int maxConcurrency;
        private Duration timeout;

        private Builder(SubagentRoster roster) {
            this.roster = roster;
        }

        public Builder synthesizer(Synthesizer synthesizer) {
            this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer");
            return this;
        }

        /**
         * Runs subagents on {@code executor} instead of a fresh per-call
         * virtual-thread executor. The supervisor does not take ownership: the
         * caller is responsible for its lifecycle (and must not shut it down while
         * a {@code fanOut} is in flight).
         */
        public Builder executor(ExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Caps how many subagents run concurrently (default unbounded). Applies
         * regardless of the executor: excess subagents wait for a slot rather than
         * issuing simultaneous LLM calls.
         */
        public Builder maxConcurrency(int maxConcurrency) {
            if (maxConcurrency < 1) {
                throw new IllegalArgumentException("maxConcurrency must be >= 1, was " + maxConcurrency);
            }
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * Sets an overall deadline for a {@code fanOut} (default none). A subagent
         * still running when it elapses is cancelled and recorded as a failed,
         * timed-out outcome; the rest are unaffected.
         */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive, was " + timeout);
            }
            this.timeout = timeout;
            return this;
        }

        public Supervisor build() {
            return new Supervisor(this);
        }
    }
}
