package dev.agentkit.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentkit.core.agent.AgentResult;
import dev.agentkit.core.agent.StopReason;
import dev.agentkit.core.llm.TokenUsage;
import org.junit.jupiter.api.Test;

/** Unit tests for the serializable boundary records (no Temporal environment). */
class DurableTypesTest {

    @Test
    void durableAgentOptionsRejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> new DurableAgentOptions(0, 1, 60, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DurableAgentOptions(60, 1, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durableAgentOptionsRejectsMaxAttemptsBelowOne() {
        assertThatThrownBy(() -> new DurableAgentOptions(60, 0, 60, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DurableAgentOptions(60, 1, 60, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durableAgentOptionsDefaultsAreSensible() {
        DurableAgentOptions defaults = DurableAgentOptions.defaults();
        assertThat(defaults.llmMaxAttempts()).isGreaterThanOrEqualTo(1);
        assertThat(defaults.llmStartToCloseSeconds()).isPositive();
    }

    @Test
    void agentRunResultFromCarriesErrorMessage() {
        AgentResult failed = AgentResult.failed(new IllegalStateException("boom"), 2,
                new TokenUsage(1, 1));
        AgentRunResult durable = AgentRunResult.from(failed);

        assertThat(durable.stopReason()).isEqualTo(StopReason.ERROR);
        assertThat(durable.errorMessage()).isEqualTo("boom");
        assertThat(durable.steps()).isEqualTo(2);
        assertThat(durable.isSuccess()).isFalse();
    }

    @Test
    void agentRunResultFromSuccessHasEmptyErrorMessage() {
        AgentRunResult durable = AgentRunResult.from(
                AgentResult.completed("done", 1, TokenUsage.ZERO));
        assertThat(durable.isSuccess()).isTrue();
        assertThat(durable.errorMessage()).isEmpty();
    }
}
