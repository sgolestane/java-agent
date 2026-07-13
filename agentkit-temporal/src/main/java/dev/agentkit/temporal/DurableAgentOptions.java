package dev.agentkit.temporal;

/**
 * Tuning knobs for the activities a durable run issues, carried in the workflow
 * input so they replay deterministically (rather than being baked into the
 * workflow code).
 *
 * <p>Timeouts are start-to-close per activity attempt; {@code maxAttempts}
 * bounds Temporal's automatic retry. The LLM activity is expected to fail
 * transiently (rate limits, network blips) and so retries; the tool activity
 * already converts a failed tool into an error result the model reacts to, so
 * its retry count guards only against genuinely transient infrastructure faults.
 *
 * @param llmStartToCloseSeconds  per-attempt timeout for an LLM call
 * @param llmMaxAttempts          max attempts for an LLM call (>= 1)
 * @param toolStartToCloseSeconds per-attempt timeout for a tool call
 * @param toolMaxAttempts         max attempts for a tool call (>= 1)
 */
public record DurableAgentOptions(long llmStartToCloseSeconds, int llmMaxAttempts,
                                  long toolStartToCloseSeconds, int toolMaxAttempts) {

    public DurableAgentOptions {
        if (llmStartToCloseSeconds <= 0 || toolStartToCloseSeconds <= 0) {
            throw new IllegalArgumentException("start-to-close timeouts must be > 0");
        }
        if (llmMaxAttempts < 1 || toolMaxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }

    /** Sensible defaults: 120s/4 attempts for LLM calls, 60s/3 for tools. */
    public static DurableAgentOptions defaults() {
        return new DurableAgentOptions(120, 4, 60, 3);
    }
}
